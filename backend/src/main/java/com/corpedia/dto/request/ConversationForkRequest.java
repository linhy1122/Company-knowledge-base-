package com.corpedia.dto.request;

/**
 * 跨会话续接请求体：POST /api/conversations/{id}/fork。
 * title 可空，默认「原标题 的续接」。
 */
public record ConversationForkRequest(
        String title
) {
}