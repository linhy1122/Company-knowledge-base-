package com.corpedia.dto.request;

import java.util.List;

/**
 * 更新会话请求体：PUT /api/conversations/{id}。
 * modelId / kbIds 为功能扩展 04 预留，本期仅重命名（title）。
 */
public record ConversationUpdateRequest(
        String title,
        String modelId,
        List<Long> kbIds
) {
}