package com.corpedia.ai;

import com.corpedia.common.Constants;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * RAG 检索：query → EmbeddingModel 向量化 → VectorStore.similaritySearch → 片段+相似度（按相似度降序）。
 * 阶段3 为无权限过滤的基础检索；权限 Metadata Filter 注入与 Rerank 在阶段4 增强。
 */
@Service
public class RagRetrieveService {

    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;

    public RagRetrieveService(EmbeddingModel embeddingModel, VectorStore vectorStore) {
        this.embeddingModel = embeddingModel;
        this.vectorStore = vectorStore;
    }

    /** 检索 topK 个相似片段（无权限过滤，阶段3 兼容入口），按相似度降序返回。 */
    public List<RetrievedChunk> retrieve(String query, int topK) {
        return retrieve(query, topK, null);
    }

    /**
     * 检索 topK 个相似片段并按相似度降序返回。
     *
     * @param filterExpr Milvus 标量过滤表达式（裸键 DSL，如 department_id in [0, 1] && permission_level <= 1）；
     *                   Spring AI 转换器自动包装为 metadata["..."]。null/空串 = 不过滤。
     */
    public List<RetrievedChunk> retrieve(String query, int topK, String filterExpr) {
        // 显式向量化 query（bge-m3）；VectorStore 内部亦会对 query 向量化，此处保持与检索一致
        SearchRequest.Builder builder = SearchRequest.builder().query(query).topK(topK);
        if (filterExpr != null && !filterExpr.isBlank()) {
            builder.filterExpression(filterExpr);
        }
        List<Document> hits = vectorStore.similaritySearch(builder.build());
        return hits.stream()
                .map(this::toChunk)
                .sorted(Comparator.comparingDouble(RetrievedChunk::similarity).reversed())
                .toList();
    }

    /** 重排：取相似度最高的前 n 条（输入已按相似度降序；LM Studio 无原生 Rerank 端点，按分数截断）。 */
    public List<RetrievedChunk> rerank(List<RetrievedChunk> top, int n) {
        if (top == null || top.isEmpty()) {
            return List.of();
        }
        return top.stream().limit(Math.max(0, n)).toList();
    }

    private RetrievedChunk toChunk(Document d) {
        Object docIdObj = d.getMetadata().get(Constants.META_DOCUMENT_ID);
        Long docId = (docIdObj instanceof Number n) ? n.longValue() : null;
        Object titleObj = d.getMetadata().get(Constants.META_TITLE);
        String title = titleObj == null ? "" : titleObj.toString();
        double score = d.getScore() == null ? 0.0 : d.getScore();
        // 功能扩展01: 读取 chunk 在原文中的字符区间（旧数据缺失时可空）
        Integer chunkStart = asInt(d.getMetadata().get(Constants.META_CHUNK_START));
        Integer chunkEnd = asInt(d.getMetadata().get(Constants.META_CHUNK_END));
        return new RetrievedChunk(docId, title, d.getId(), d.getText(), score, chunkStart, chunkEnd);
    }

    private Integer asInt(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }
}
