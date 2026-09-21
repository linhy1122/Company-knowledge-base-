package com.corpedia.ai;

/**
 * 检索命中的知识片段（含相似度）。
 */
public record RetrievedChunk(
        Long documentId,
        String title,
        String chunkId,
        String content,
        double similarity,
        Integer chunkStart,
        Integer chunkEnd
) {
}