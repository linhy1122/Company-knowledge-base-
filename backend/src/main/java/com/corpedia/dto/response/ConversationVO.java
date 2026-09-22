package com.corpedia.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * 会话返回体。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConversationVO(
        Long id,
        String title,
        Boolean archived,
        LocalDateTime createdAt
) {
}