package com.corpedia.ai;

import com.corpedia.common.Constants;
import com.corpedia.config.MilvusSchemaInitializer;
import com.corpedia.config.RagProperties;
import com.corpedia.entity.KbDocument;
import com.corpedia.entity.KnowledgeBase;
import com.corpedia.mapper.KbDocumentMapper;
import com.corpedia.mapper.KnowledgeBaseMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.FileSystemResource;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档入库管道：Tika/文本解析 → 清洗 → 分块(500/100) → 拼 metadata → VectorStore.add（内部自动 Embedding）
 * → 更新 document.status/chunk_count。异步执行 + @Retryable 兜底，最终失败落 FAILED。
 */
@Service
public class DocumentPipelineService {

    private static final Logger log = LoggerFactory.getLogger(DocumentPipelineService.class);

    private final KbDocumentMapper documentMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final MilvusSchemaInitializer milvus;
    private final VectorStore vectorStore;
    private final TextCleaner textCleaner;
    private final Chunker chunker;
    private final RagProperties rag;
    /** 自注入代理，确保 ingest -> runPipeline 经过 @Async/@Retryable 代理。 */
    private final DocumentPipelineService self;

    public DocumentPipelineService(KbDocumentMapper documentMapper,
                                   KnowledgeBaseMapper kbMapper,
                                   MilvusSchemaInitializer milvus,
                                   VectorStore vectorStore,
                                   TextCleaner textCleaner,
                                   Chunker chunker,
                                   RagProperties rag,
                                   @Lazy DocumentPipelineService self) {
        this.documentMapper = documentMapper;
        this.kbMapper = kbMapper;
        this.milvus = milvus;
        this.vectorStore = vectorStore;
        this.textCleaner = textCleaner;
        this.chunker = chunker;
        this.rag = rag;
        this.self = self;
    }

    /** 触发异步管道（上传后/手动重试共用），立即返回，状态置 PARSING。 */
    public void ingest(Long documentId) {
        self.runPipeline(documentId);
    }

    @Async("documentPipelineExecutor")
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1500))
    public void runPipeline(Long documentId) {
        KbDocument doc = documentMapper.selectById(documentId);
        if (doc == null) {
            log.warn("[Pipeline] 文档 {} 不存在，跳过", documentId);
            return;
        }
        doc.setStatus(Constants.DOC_PARSING);
        doc.setChunkCount(0);
        documentMapper.updateById(doc);
        try {
            KnowledgeBase kb = kbMapper.selectById(doc.getKbId());
            String raw = parseFile(doc);
            String cleaned = textCleaner.clean(raw);
            if (cleaned.isEmpty()) {
                throw new IllegalStateException("解析后文本为空");
            }
            List<Document> chunks = chunker.split(doc.getId(), cleaned, buildMetadata(doc, kb));
            // 幂等：重处理前先清旧 chunk
            milvus.deleteByDocumentId(doc.getId());
            vectorStore.add(chunks);
            doc.setStatus(Constants.DOC_READY);
            doc.setChunkCount(chunks.size());
            doc.setCleanedText(cleaned);   // 功能扩展01: 持久化清洗后全文，供引用溯源/原文高亮
            documentMapper.updateById(doc);
            log.info("[Pipeline] 文档 {} ({}) 入库完成, chunks={}", doc.getId(), doc.getFilename(), chunks.size());
        } catch (Exception e) {
            log.error("[Pipeline] 文档 {} ({}) 处理失败: {}", doc.getId(), doc.getFilename(), e.getMessage(), e);
            doc.setStatus(Constants.DOC_FAILED);
            doc.setChunkCount(0);
            documentMapper.updateById(doc);
            throw new RuntimeException("文档处理失败: " + e.getMessage(), e);
        }
    }

    /** 解析文件内容：md/txt 直接 UTF-8 读，pdf/docx 等走 Tika。 */
    private String parseFile(KbDocument doc) throws Exception {
        Path path = Path.of(doc.getFilePath());
        String type = doc.getFileType() == null ? "" : doc.getFileType().toLowerCase();
        if (type.equals("md") || type.equals("txt")) {
            return Files.readString(path, StandardCharsets.UTF_8);
        }
        TikaDocumentReader reader = new TikaDocumentReader(new FileSystemResource(path), ExtractedTextFormatter.defaults());
        StringBuilder sb = new StringBuilder();
        for (Document d : reader.get()) {
            sb.append(d.getText()).append('\n');
        }
        return sb.toString();
    }

    /** Milvus chunk metadata：document_id / title / department_id(0=全司) / permission_level(0/1/2) / category / source。 */
    private Map<String, Object> buildMetadata(KbDocument doc, KnowledgeBase kb) {
        Map<String, Object> meta = new HashMap<>();
        meta.put(Constants.META_DOCUMENT_ID, doc.getId());
        meta.put(Constants.META_TITLE, doc.getFilename());
        // 文档级部门优先（权限设置可独立修改），否则回退知识库部门；全司=0
        Integer dept = doc.getDepartmentId() != null
                ? doc.getDepartmentId().intValue()
                : (kb != null && kb.getDepartmentId() != null ? kb.getDepartmentId().intValue() : 0);
        meta.put(Constants.META_DEPARTMENT_ID, dept);
        meta.put(Constants.META_PERMISSION_LEVEL, Constants.permissionLevelToInt(doc.getPermissionLevel()));
        meta.put(Constants.META_CATEGORY, kb == null ? "" : kb.getName());
        meta.put(Constants.META_SOURCE, doc.getFilename());
        return meta;
    }
}
