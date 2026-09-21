package com.corpedia.service;

import com.corpedia.ai.ChatResult;
import com.corpedia.ai.RagChatService;
import com.corpedia.dto.response.SendMessageResultVO;
import com.corpedia.dto.response.SourceVO;
import com.corpedia.entity.Conversation;
import com.corpedia.entity.Message;
import com.corpedia.mapper.ConversationMapper;
import com.corpedia.mapper.MessageMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    private final ConversationService conversationService;
    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final RagChatService ragChat;
    private final ObjectMapper objectMapper;

    public MessageService(ConversationService conversationService,
                          MessageMapper messageMapper,
                          ConversationMapper conversationMapper,
                          RagChatService ragChat,
                          ObjectMapper objectMapper) {
        this.conversationService = conversationService;
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
        this.ragChat = ragChat;
        this.objectMapper = objectMapper;
    }

    /** 提问主链路：落库 USER 问题 → 检索+生成 → 落库 ASSISTANT 答案 → 返回。 */
    @Transactional
    public SendMessageResultVO ask(Long userId, Long conversationId, String content) {
        Conversation conv = conversationService.requireConversation(userId, conversationId);
        // 首条消息自动填充会话标题（前端可能未显式命名）
        if (conv.getTitle() == null || conv.getTitle().isBlank()) {
            conv.setTitle(content.length() > 30 ? content.substring(0, 30) : content);
            conversationMapper.updateById(conv);
        }

        Message userMsg = new Message();
        userMsg.setConversationId(conversationId);
        userMsg.setRole("USER");
        userMsg.setContent(content);
        messageMapper.insert(userMsg);

        long start = System.currentTimeMillis();
        ChatResult result = ragChat.chat(userId, conversationId, content);
        long elapsedMs = System.currentTimeMillis() - start;

        Message assistant = new Message();
        assistant.setConversationId(conversationId);
        assistant.setRole("ASSISTANT");
        assistant.setContent(result.content());
        assistant.setSimilarity(result.similarity());
        assistant.setAnswered(result.answered() ? 1 : 0);
        assistant.setResponseMs(elapsedMs);
        if (!result.sources().isEmpty()) {
            assistant.setSources(toJson(result.sources().stream()
                    .map(c -> new SourceVO(c.documentId(), c.title(), c.chunkId(), c.similarity(), c.chunkStart(), c.chunkEnd()))
                    .toList()));
        }
        messageMapper.insert(assistant);

        List<SourceVO> sources = result.sources().stream()
                .map(c -> new SourceVO(c.documentId(), c.title(), c.chunkId(), c.similarity(), c.chunkStart(), c.chunkEnd()))
                .toList();
        return new SendMessageResultVO(assistant.getId(), result.content(), sources, result.answered());
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