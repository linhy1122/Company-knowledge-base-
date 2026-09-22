package com.corpedia.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 自定义 RAG 参数（application.yml corpedia.rag.*）。
 * 
 *  * 自定义 RAG 参数（application.yml corpedia.rag.*）。
 *  * 自定义 RAG 参数（application.yml corpedia.rag.*）。
 */
@Data
@ConfigurationProperties(prefix = "corpedia.rag")
public class RagProperties {

    private int chunkSize = 500;
    private int chunkOverlap = 100;
    private int topK = 10;
    private int rerankTop = 3;
    private double similarityThreshold = 0.6;
    private String collection = "knowledge_chunks";
    private int historyMessages = 6;      // 阶段4: 拼入 Prompt 的最近消息条数（含 USER/ASSISTANT）
}
