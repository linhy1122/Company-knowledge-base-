package com.corpedia.service;

import com.corpedia.ai.ChatPlan;
import com.corpedia.ai.RagChatService;
import com.corpedia.ai.RetrievedChunk;
import com.corpedia.dto.response.SourceVO;
import com.corpedia.entity.Conversation;
import com.corpedia.entity.Message;
import com.corpedia.mapper.ConversationMapper;
import com.corpedia.mapper.MessageMapper;
import com.corpedia.security.UserContext;
import com.corpedia.security.UserContextHolder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SSE 流式问答：在独立线程推流，事件协议为每行 {@code data: <json>\n\n}，
 * 顺序 meta → sources → delta… → done；异常发 error。落库与裁剪：USER 前置提交，ASSISTANT 完成后落库。
 */
@Service
public class StreamingChatService {

    private static final Logger log = LoggerFactory.getLogger(StreamingChatService.class);

    /** 达到该字符数即推一个 delta，兼顾打字机节奏与推流开销。 */
    private static final int DELTA_BATCH = 12;

    private final ConversationService conversationService;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final RagChatService ragChat;
    private final ObjectMapper objectMapper;

    public StreamingChatService(ConversationService conversationService,
                                ConversationMapper conversationMapper,
                                MessageMapper messageMapper,
                                RagChatService ragChat,
                                ObjectMapper objectMapper) {
        this.conversationService = conversationService;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.ragChat = ragChat;
        this.objectMapper = objectMapper;
    }

    /**
     * 主推流流程。在 sseExecutor 线程执行（异步，控制器立即拿到 SseEmitter 返回）。
     * userCtx 在请求线程捕获后带入，赋值到本任务的 UserContextHolder，保证权限判定（canUseSource 等线程本地）在异步线程仍然生效。
     */
    @Async("sseExecutor")
    public void stream(UserContext userCtx, Long userId, Long conversationId, String question, SseEmitter emitter) {
        UserContextHolder.set(userCtx);
        try {
            doStream(userId, conversationId, question, emitter);
        } finally {
            UserContextHolder.clear();
        }
    }

    private void doStream(Long userId, Long conversationId, String question, SseEmitter emitter) {
        long start = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        StringBuilder pending = new StringBuilder();

        try {
            Conversation conv = conversationService.requireConversation(userId, conversationId);
            // 回访已归档会话 → 自动恢复为进行中；首条消息自动填充标题（与阻塞版一致）
            if (conv.getStatus() != null && conv.getStatus() == 0) {
                conv.setStatus(1);
            }
            if (conv.getTitle() == null || conv.getTitle().isBlank()) {
                conv.setTitle(question.length() > 30 ? question.substring(0, 30) : question);
            }
            conversationMapper.updateById(conv);

            // USER 消息独立提交（不在长流中持有事务）
            Message userMsg = new Message();
            userMsg.setConversationId(conversationId);
            userMsg.setRole("USER");
            userMsg.setContent(question);
            messageMapper.insert(userMsg);

            send(emitter, "meta", Map.of("conversationId", conversationId));

            ChatPlan plan = ragChat.prepare(userId, conversationId, question);
            // 快照为 final，供订阅回调安全引用
            final long fConvId = conversationId;
            final List<SourceVO> fSources = toSources(plan.sources());
            final boolean fAnswered = plan.answered();
            final double fSimilarity = plan.similarity();
            send(emitter, "sources", Map.of(
                    "sources", fSources,
                    "answered", fAnswered,
                    "similarity", fSimilarity));

            ragChat.chatClient().prompt()
                    .system(plan.systemPrompt())
                    .user(plan.userPrompt())
                    .stream()
                    .content()
                    .subscribe(
                            delta -> {
                                sb.append(delta);
                                pending.append(delta);
                                if (pending.length() >= DELTA_BATCH) {
                                    flushDelta(emitter, pending);
                                }
                            },
                            err -> {
                                // 模型流中断：尽力落库已累计内容后结束
                                log.warn("[SSE] 流式生成出错，落库已累计内容: {}", err.getMessage());
                                Message partial = persistAssistant(fConvId, sb.toString(),
                                        fSources, fAnswered && !sb.isEmpty(), fSimilarity,
                                        System.currentTimeMillis() - start);
                                sendError(emitter, err.getMessage());
                                emitter.complete();
                            },
                            () -> {
                                flushDelta(emitter, pending);
                                String raw = sb.toString();
                                // 工具兜底路径：答到拒答文案视为拒答
                                boolean finalAnswered = fAnswered;
                                String finalContent = raw;
                                if (!fAnswered && (raw.isBlank() || raw.contains(RagChatService.FALLBACK_REFUSE))) {
                                    finalAnswered = false;
                                    finalContent = RagChatService.FALLBACK_REFUSE;
                                } else if (!fAnswered) {
                                    finalAnswered = true;
                                }
                                Message assistant = persistAssistant(fConvId, finalContent, fSources,
                                        finalAnswered, fSimilarity, System.currentTimeMillis() - start);
                                send(emitter, "done", Map.of(
                                        "messageId", assistant.getId(),
                                        "content", finalContent,
                                        "answered", finalAnswered));
                                emitter.complete();
                            });
        } catch (Exception e) {
            log.error("[SSE] 推流前置异常", e);
            sendError(emitter, e.getMessage());
            emitter.complete();
        }
    }

    private void flushDelta(SseEmitter emitter, StringBuilder pending) {
        if (pending.length() == 0) {
            return;
        }
        send(emitter, "delta", Map.of("content", pending.toString()));
        pending.setLength(0);
    }

    /** 推一个事件；客户端断开时静默忽略（后台仍继续累计，onComplete 时落库）。 */
    private void send(SseEmitter emitter, String type, Map<String, Object> payload) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", type);
        if (payload != null) {
            ev.putAll(payload);
        }
        try {
            emitter.send(objectMapper.writeValueAsString(ev));
        } catch (IOException | IllegalStateException e) {
            log.debug("[SSE] 推送 {} 事件失败（客户端可能已断开）: {}", type, e.getMessage());
        }
    }

    private void sendError(SseEmitter emitter, String message) {
        send(emitter, "error", Map.of("message", message == null ? "生成失败" : message));
    }

    private Message persistAssistant(Long conversationId, String content, List<SourceVO> sources,
                                     boolean answered, double similarity, long responseMs) {
        Message assistant = new Message();
        assistant.setConversationId(conversationId);
        assistant.setRole("ASSISTANT");
        assistant.setContent(content);
        assistant.setSimilarity(similarity);
        assistant.setAnswered(answered ? 1 : 0);
        assistant.setResponseMs(responseMs);
        if (!sources.isEmpty()) {
            assistant.setSources(toJson(sources));
        }
        messageMapper.insert(assistant);
        return assistant;
    }

    private List<SourceVO> toSources(List<RetrievedChunk> chunks) {
        return chunks.stream()
                .map(c -> new SourceVO(c.documentId(), c.title(), c.chunkId(), c.similarity(), c.chunkStart(), c.chunkEnd()))
                .toList();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("来源 JSON 序列化失败: {}", e.getMessage());
            return null;
        }
    }
}