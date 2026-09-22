package com.corpedia.ai;

import com.corpedia.common.Constants;
import com.corpedia.config.RagProperties;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分块：按字符窗口 500/100 切分，优先在换行处断开；块 id 确定性生成（doc-{documentId}-{idx}），支持幂等重入库。
 */
@Component
public class Chunker {

    private final RagProperties rag;

    public Chunker(RagProperties rag) {
        this.rag = rag;
    }

    public List<Document> split(Long documentId, String text, Map<String, Object> metadata) {
        List<Document> out = new ArrayList<>();
        int size = rag.getChunkSize();
        int overlap = rag.getChunkOverlap();
        if (size <= 0 || overlap < 0 || overlap >= size) {
            throw new IllegalArgumentException("Require chunkSize > chunkOverlap >= 0");
        }
        int len = text.length();
        int start = 0;
        int idx = 0;
        while (start < len) {
            int end = Math.min(start + size, len);
            // 尽量在换行处断开，避免切断句子
            if (end < len) {
                int nl = text.lastIndexOf('\n', end);
                if (nl > start + size / 2) {
                    end = nl;
                }
            }
            String raw = text.substring(start, end);
            String chunkText = raw.strip();
            if (!chunkText.isEmpty()) {
                // 功能扩展01: 推算 strip 后子串在原文 text 中的首末字符区间，供引用溯源/原文高亮定位
                int leadWs = raw.length() - raw.stripLeading().length();
                int chunkStart = start + leadWs;
                Map<String, Object> meta = new HashMap<>(metadata);
                meta.put("chunk_index", idx);
                meta.put(Constants.META_CHUNK_START, chunkStart);
                meta.put(Constants.META_CHUNK_END, chunkStart + chunkText.length());
                out.add(new Document("doc-" + documentId + "-" + idx, chunkText, meta));
            }
            if (end >= len) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
            idx++;
        }
        return out;
    }
}
