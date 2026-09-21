package com.corpedia.ai;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.corpedia.config.RagProperties;
import com.corpedia.entity.Message;
import com.corpedia.mapper.MessageMapper;
import com.corpedia.service.PermissionService;
import com.corpedia.service.ResourceAccessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 问答生成（阶段4 增强）：权限过滤检索 → 相似度阈值定答/拒答 → 多轮历史 + Context 拼 Prompt →
 * ChatClient 生成答案，附来源。
 * 结构化输出不做 JSON schema（本地 <7B 模型不可靠），改为「固定格式提示 + 直接返回正文」。
 */
@Service
public class RagChatService {

    private static final Logger log = LoggerFactory.getLogger(RagChatService.class);

    /** 拒答兜底文案（与前端契约一致）。 */
    public static final String FALLBACK_REFUSE = "当前企业知识库中未找到足够可靠的信息，建议联系相关部门确认。";

    private static final String SYSTEM_PROMPT = """
            你是企业内部知识库智能助手。请严格依据提供的检索片段回答用户问题：
            1. 只依据检索片段中的信息作答，禁止编造、不得依据片段之外的内容。
            2. 答案需准确、简洁，可直接引用片段中的条款/数字/政策。
            3. 若片段确实无法回答该问题，请明确说明知识库中未找到相关信息。
            4. 如用户问题依赖上下文（如"那报销呢？"），可结合历史对话理解指代，但最终答案仍须以当前检索片段为准。
            5. 全程使用中文。
            """;

    /** 检索为空时的工具兜底提示：允许调用天气工具回答实时类问题，否则返回拒答文案。 */
    private static final String TOOL_FALLBACK_SYSTEM_PROMPT = """
            你是企业内部知识库智能助手，具备调用外部工具的能力。
            当前企业知识库中未检索到足以回答该问题的相关片段（片段为空）。
            规则：
            1. 如果用户的问题是实时天气查询（如"XX 今天天气如何"），请调用 query_weather 工具获取天气后用中文如实回答。
            2. 除天气等可通过已提供工具回答的实时查询外，其余问题不要编造，只回复以下拒答文案：
            %s
            3. 全程使用中文。
            """.formatted(FALLBACK_REFUSE);

    private final RagRetrieveService retrieveService;
    private final RagProperties rag;
    private final ChatClient chatClient;
    private final PermissionService permissionService;
    private final MessageMapper messageMapper;
    private final ResourceAccessService access;

    public RagChatService(RagRetrieveService retrieveService, RagProperties rag, ChatClient.Builder chatClientBuilder,
                          PermissionService permissionService, MessageMapper messageMapper, ResourceAccessService access,
                          AmapWeatherTool amapWeatherTool) {
        this.retrieveService = retrieveService;
        this.rag = rag;
        this.chatClient = chatClientBuilder.defaultTools(amapWeatherTool).build();
        this.permissionService = permissionService;
        this.messageMapper = messageMapper;
        this.access = access;
    }

    /**
     * 生成前阶段：权限过滤检索 → 阈值定答 → Rerank → 拼 Prompt，返回 {system, user, sources, answered, similarity}。
     * 流式与非流式共用本方法，保证两种入口的检索结论、阈值判定与来源完全一致。
     */
    public ChatPlan prepare(Long userId, Long conversationId, String question) {
        String filter = permissionService.buildVectorFilter(userId);
        long start = System.currentTimeMillis();
        List<RetrievedChunk> hits = retrieveService.retrieve(question, rag.getTopK(), filter);

        // 阈值过滤：低于 similarity-threshold 视为无可靠依据
        double threshold = rag.getSimilarityThreshold();
        List<RetrievedChunk> relevant = hits.stream()
                .filter(h -> h.similarity() >= threshold)
                .filter(h -> access.canUseSource(h.documentId()))
                .toList();
        if (relevant.isEmpty()) {
            // 知识库无可靠片段：让 AI 自主判断——可调用天气工具回答实时查询，否则返回拒答文案
            double best = hits.isEmpty() ? 0.0 : hits.get(0).similarity();
            log.info("[RagChat] prepare 检索片段不足，转工具兜底（最高分 {}，filter={}），耗时 {}ms",
                    hits.isEmpty() ? "N/A" : String.format("%.4f", best),
                    filter, System.currentTimeMillis() - start);
            return new ChatPlan(TOOL_FALLBACK_SYSTEM_PROMPT, question, List.of(), false, best);
        }

        // Rerank：取相似度最高的前 rerankTop 条作为来源上下文
        List<RetrievedChunk> sources = retrieveService.rerank(relevant, rag.getRerankTop());
        double best = sources.get(0).similarity();

        String context = sources.stream()
                .map(c -> "[片段]\n" + c.content())
                .collect(Collectors.joining("\n\n"));

        String history = loadHistory(conversationId);

        String userPrompt = """
                问题：%s

                %s以下是企业知识库检索到的相关片段，请据此作答：
                %s
                """.formatted(question, history, context);

        log.info("[RagChat] prepare 定答（相似度 {}/{}，共引用 {} 条，filter={}），耗时 {}ms",
                best, threshold, sources.size(), filter, System.currentTimeMillis() - start);
        return new ChatPlan(SYSTEM_PROMPT, userPrompt, sources, true, best);
    }

    /** 一次问答：权限过滤检索 → 阈值定答 → 多轮历史+上下文生成。sources 为用于取答的引用片段。 */
    public ChatResult chat(Long userId, Long conversationId, String question) {
        long start = System.currentTimeMillis();
        ChatPlan plan = prepare(userId, conversationId, question);

        String answer = chatClient.prompt()
                .system(plan.systemPrompt())
                .user(plan.userPrompt())
                .call()
                .content();
        double best = plan.similarity();
        if (answer == null || answer.isBlank()) {
            return new ChatResult(FALLBACK_REFUSE, List.of(), false, best);
        }
        if (!plan.answered()) {
            // 工具兜底路径：答到拒答文案视为拒答
            if (answer.contains(FALLBACK_REFUSE)) {
                return new ChatResult(FALLBACK_REFUSE, List.of(), false, best);
            }
            log.info("[RagChat] 通过工具回答成功，耗时 {}ms", System.currentTimeMillis() - start);
            return new ChatResult(answer.strip(), List.of(), true, best);
        }
        log.info("[RagChat] 定答，耗时 {}ms", System.currentTimeMillis() - start);
        return new ChatResult(answer.strip(), plan.sources(), true, best);
    }

    /** 暴露配置好工具链的 ChatClient，供流式链路调用（与阻塞版同一实例，工具/模型一致）。 */
    public ChatClient chatClient() {
        return chatClient;
    }

    /** 取会话最近 N 条消息作为多轮上下文（USER/ASSISTANT 交替，按 id 升序）。 */
    private String loadHistory(Long conversationId) {
        if (conversationId == null || rag.getHistoryMessages() <= 0) {
            return "";
        }
        List<Message> msgs = messageMapper.selectList(new QueryWrapper<Message>()
                .eq("conversation_id", conversationId)
                .orderByDesc("id")
                .last("limit " + rag.getHistoryMessages()));
        // 恢复时间正序
        java.util.Collections.reverse(msgs);
        String history = msgs.stream()
                .map(m -> ("USER".equals(m.getRole()) ? "用户：" : "助手：") + m.getContent())
                .collect(Collectors.joining("\n"));
        return history.isBlank() ? "" : "历史对话（仅供参考理解指代，回答以检索片段为准）：\n" + history + "\n\n";
    }
}
