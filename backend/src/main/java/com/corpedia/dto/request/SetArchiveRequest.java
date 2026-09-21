package com.corpedia.dto.request;

/**
 * 归档 / 取消归档请求体：PUT /api/conversations/{id}/archive。
 */
public record SetArchiveRequest(
        boolean archived
) {
}