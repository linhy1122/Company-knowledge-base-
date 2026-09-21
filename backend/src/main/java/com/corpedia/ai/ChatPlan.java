package com.corpedia.ai;

import java.util.List;

/**
 * RAG 生成前已确定的信息：来源 / 是否定答 / 最大相似度 + 拼好的 ChatClient 提示词。
 * 供流式（SSE）与非流式共用，保证两种入口的检索结论与答案一致。
 */
public record ChatPlan(
        String systemPrompt,
        String userPrompt,
        List<RetrievedChunk> sources,
        boolean answered,
        double similarity
) {
}