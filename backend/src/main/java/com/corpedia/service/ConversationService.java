package com.corpedia.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.corpedia.common.BusinessException;
import com.corpedia.common.ResultCode;
import com.corpedia.dto.request.ConversationCreateRequest;
import com.corpedia.dto.request.ConversationUpdateRequest;
import com.corpedia.dto.response.ConversationVO;
import com.corpedia.dto.response.MessageVO;
import com.corpedia.dto.response.SourceVO;
import com.corpedia.entity.Conversation;
import com.corpedia.entity.Message;
import com.corpedia.mapper.ConversationMapper;
import com.corpedia.mapper.MessageMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ConversationService {

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final ObjectMapper objectMapper;

    public ConversationService(ConversationMapper conversationMapper,
                               MessageMapper messageMapper,
                               ObjectMapper objectMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
    }

    /** 当前用户会话列表。scope: active(默认)/archived/all，按创建时间倒序。 */
    public List<ConversationVO> list(Long userId, String scope) {
        QueryWrapper<Conversation> qw = new QueryWrapper<Conversation>().eq("user_id", userId);
        if ("archived".equalsIgnoreCase(scope)) {
            qw.eq("status", 0);
        } else if ("all".equalsIgnoreCase(scope)) {
            // 不过滤
        } else {
            qw.eq("status", 1); // active / 默认
        }
        qw.orderByDesc("id");
        return conversationMapper.selectList(qw).stream().map(this::toVO).toList();
    }

    public ConversationVO create(Long userId, Long departmentId, ConversationCreateRequest req) {
        Conversation c = new Conversation();
        c.setUserId(userId);
        c.setDepartmentId(departmentId);
        c.setTitle(req != null && req.title() != null && !req.title().isBlank() ? req.title() : null);
        c.setStatus(1);
        conversationMapper.insert(c);
        return toVO(c);
    }

    /** 更新会话（本期仅 title 重命名；modelId/kbIds 由功能04 使用）。 */
    public ConversationVO update(Long userId, Long id, ConversationUpdateRequest req) {
        Conversation c = requireConversation(userId, id);
        if (req != null && req.title() != null) {
            c.setTitle(req.title().isBlank() ? fallbackTitle(c) : req.title());
        }
        // modelId / kbIds 预留（功能04），本期不落库
        conversationMapper.updateById(c);
        return toVO(c);
    }

    /** 归档 / 取消归档：status 0 已归档 / 1 正常。 */
    public void setArchived(Long userId, Long id, boolean archived) {
        Conversation c = requireConversation(userId, id);
        int target = archived ? 0 : 1;
        if (c.getStatus() == null || c.getStatus() != target) {
            c.setStatus(target);
            conversationMapper.updateById(c);
        }
    }

    /** 删除会话及其全部消息。 */
    @Transactional
    public void delete(Long userId, Long id) {
        requireConversation(userId, id);
        messageMapper.delete(new QueryWrapper<Message>().eq("conversation_id", id));
        conversationMapper.deleteById(id);
    }

    /**
     * 跨会话续接：以源会话为模板复制一份新会话（含全部历史消息），可在新会话继续提问。
     */
    @Transactional
    public ConversationVO fork(Long userId, Long sourceId, String title) {
        Conversation source = requireConversation(userId, sourceId);

        Conversation c = new Conversation();
        c.setUserId(source.getUserId());
        c.setDepartmentId(source.getDepartmentId());
        c.setTitle(title != null && !title.isBlank()
                ? title
                : (source.getTitle() != null ? source.getTitle() + " 的续接" : null));
        c.setStatus(1);
        conversationMapper.insert(c);

        List<Message> msgs = messageMapper.selectList(new QueryWrapper<Message>()
                .eq("conversation_id", sourceId)
                .orderByAsc("id"));
        for (Message m : msgs) {
            Message copy = new Message();
            copy.setConversationId(c.getId());
            copy.setRole(m.getRole());
            copy.setContent(m.getContent());
            copy.setSources(m.getSources());
            copy.setSimilarity(m.getSimilarity());
            copy.setAnswered(m.getAnswered());
            copy.setResponseMs(m.getResponseMs());
            copy.setCreatedAt(m.getCreatedAt()); // 保留源会话时间线
            messageMapper.insert(copy);
        }
        return toVO(c);
    }

    /** 会话历史消息（按创建时间升序，贴合对话顺序）。 */
    public List<MessageVO> messages(Long userId, Long id) {
        requireConversation(userId, id);
        return messageMapper.selectList(new QueryWrapper<Message>()
                        .eq("conversation_id", id)
                        .orderByAsc("id"))
                .stream().map(this::toVO).toList();
    }

    public Conversation requireConversation(Long userId, Long id) {
        Conversation c = conversationMapper.selectById(id);
        if (c == null || !c.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "会话不存在");
        }
        return c;
    }

    private String fallbackTitle(Conversation c) {
        if (c.getTitle() != null && !c.getTitle().isBlank()) {
            return c.getTitle();
        }
        Message first = messageMapper.selectOne(new QueryWrapper<Message>()
                .eq("conversation_id", c.getId())
                .eq("role", "USER")
                .orderByAsc("id")
                .last("limit 1"));
        if (first != null && first.getContent() != null) {
            return first.getContent().length() > 30 ? first.getContent().substring(0, 30) : first.getContent();
        }
        return null;
    }

    private ConversationVO toVO(Conversation c) {
        return new ConversationVO(c.getId(), c.getTitle(),
                c.getStatus() != null && c.getStatus() == 0,
                c.getCreatedAt());
    }

    private MessageVO toVO(Message m) {
        List<SourceVO> sources = null;
        if (m.getSources() != null && !m.getSources().isBlank()) {
            try {
                sources = objectMapper.readValue(m.getSources(), new TypeReference<List<SourceVO>>() {
                });
            } catch (Exception e) {
                sources = List.of();
            }
        }
        Boolean answered = m.getAnswered() == null ? null : m.getAnswered() == 1;
        return new MessageVO(m.getId(), m.getRole(), m.getContent(), sources, answered, m.getCreatedAt());
    }
}