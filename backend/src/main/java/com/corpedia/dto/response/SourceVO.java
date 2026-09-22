package com.corpedia.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 答案来源片段（前端 SourceCard 用）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SourceVO(
        Long documentId,
        String title,
        String chunkId,
        double similarity,
        Integer chunkStart,
        Integer chunkEnd
) {
}