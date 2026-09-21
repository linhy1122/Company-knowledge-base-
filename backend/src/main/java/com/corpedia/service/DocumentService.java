package com.corpedia.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.corpedia.ai.DocumentPipelineService;
import com.corpedia.common.BusinessException;
import com.corpedia.common.Constants;
import com.corpedia.common.ResultCode;
import com.corpedia.config.MilvusSchemaInitializer;
import com.corpedia.config.StorageProperties;
import com.corpedia.dto.request.DocumentPermissionRequest;
import com.corpedia.dto.response.DocumentChunkVO;
import com.corpedia.dto.response.DocumentVO;
import com.corpedia.entity.KbDocument;
import com.corpedia.entity.KnowledgeBase;
import com.corpedia.mapper.KbDocumentMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class DocumentService {

    private static final Set<String> ALLOWED_TYPES = Set.of("md", "pdf", "docx", "txt");

    private final KbDocumentMapper documentMapper;
    private final KbService kbService;
    private final DocumentPipelineService pipeline;
    private final MilvusSchemaInitializer milvus;
    private final StorageProperties storage;
    private final ResourceAccessService access;

    public DocumentService(KbDocumentMapper documentMapper,
                           KbService kbService,
                           DocumentPipelineService pipeline,
                           MilvusSchemaInitializer milvus,
                           StorageProperties storage, ResourceAccessService access) {
        this.documentMapper = documentMapper;
        this.kbService = kbService;
        this.pipeline = pipeline;
        this.milvus = milvus;
        this.storage = storage;
        this.access = access;
    }

    /** 上传：落盘 + 插入 PARSING 行 + 触发异步管道。 */
    public DocumentVO upload(Long kbId, MultipartFile file, Long uploaderId) {
        KnowledgeBase kb = kbService.requireKb(kbId);
        access.requireManage(kb.getDepartmentId());
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件不能为空");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件名不能为空");
        }
        if (filename.contains("/") || filename.contains("\\") || filename.contains("..") ||
                filename.contains(":")) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件名不能包含路径");
        }
        String ext = extensionOf(filename);
        if (!ALLOWED_TYPES.contains(ext)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅支持 md/pdf/docx/txt 格式");
        }
        try {
            Path dir = Path.of(storage.getPath(), String.valueOf(kbId)).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            String storedName = UUID.randomUUID().toString().replace("-", "") + "_" + filename;
            Path target = dir.resolve(storedName).normalize();
            if (!target.startsWith(dir)) throw new BusinessException(ResultCode.BAD_REQUEST, "非法文件路径");
            file.transferTo(target.toAbsolutePath());

            KbDocument doc = new KbDocument();
            doc.setKbId(kbId);
            doc.setFilename(filename);
            doc.setFilePath(target.toAbsolutePath().toString());
            doc.setFileType(ext);
            doc.setSize(file.getSize());
            doc.setStatus(Constants.DOC_PARSING);
            doc.setChunkCount(0);
            doc.setPermissionLevel(kb.getPermissionLevel() == null ? Constants.LV_PUBLIC : kb.getPermissionLevel());
            doc.setDepartmentId(kb.getDepartmentId());   // 快照知识库所属部门（可空=全司）
            doc.setUploadedBy(uploaderId);
            documentMapper.insert(doc);

            pipeline.ingest(doc.getId());   // 异步处理，立即返回 PARSING
            return toVO(doc);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.ERROR, "文件保存失败: " + e.getMessage());
        }
    }

    public List<DocumentVO> listByKb(Long kbId) {
        kbService.requireKb(kbId);
        return documentMapper.selectList(new QueryWrapper<KbDocument>()
                        .eq("kb_id", kbId).orderByDesc("id"))
                .stream().filter(access::canRead).map(this::toVO).toList();
    }

    /** 轮询/详情端点（硬骨头3）：返回当前 status / chunkCount。 */
    public DocumentVO getDetail(Long id) {
        return toVO(requireDoc(id));
    }

    /** 批量状态查询（可选）：GET /api/documents?status=PARSING。 */
    public List<DocumentVO> listByStatus(String status, Long kbId) {
        QueryWrapper<KbDocument> qw = new QueryWrapper<>();
        if (status != null && !status.isBlank()) {
            qw.eq("status", status.toUpperCase());
        }
        if (kbId != null) {
            qw.eq("kb_id", kbId);
        }
        qw.orderByDesc("id");
        return documentMapper.selectList(qw).stream().filter(access::canRead).map(this::toVO).toList();
    }

    /** 删除文档：先清 Milvus chunk，再删文件与行。 */
    @Transactional
    public void delete(Long id) {
        KbDocument doc = requireDoc(id);
        requireManage(doc);
        if (Constants.DOC_PARSING.equals(doc.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文档正在处理中，请稍后再试");
        }
        milvus.deleteByDocumentId(id);
        try {
            Files.deleteIfExists(Path.of(doc.getFilePath()));
        } catch (IOException e) {
            // 文件缺失/占用不影响主流程，仅记录
        }
        documentMapper.deleteById(id);
    }

    public KbDocument requireDoc(Long id) {
        KbDocument doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文档不存在");
        }
        access.requireRead(doc);
        return doc;
    }

    /* ---------------- 阶段4 P1：chunk 预览 / 重新向量化 / 权限修改 ---------------- */

    /** 文档分块列表（数据来自 Milvus，按 chunk_index 升序）。 */
    public List<DocumentChunkVO> listChunks(Long id) {
        requireDoc(id);
        return milvus.queryChunksByDocumentId(id);
    }

    /** 重新向量化（P1）：对 READY/FAILED 文档重新入库；PARSING 中拒绝避免并发管道。 */
    public void reprocess(Long id) {
        KbDocument doc = requireDoc(id);
        requireManage(doc);
        if (Constants.DOC_PARSING.equals(doc.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文档正在处理中，请稍后再试");
        }
        doc.setStatus(Constants.DOC_PARSING);
        documentMapper.updateById(doc);
        pipeline.ingest(id);
    }

    /**
     * 一键重新向量化（P1）：对指定知识库下所有 READY/FAILED 文档重新入库；
     * 正在 PARSING 的文档跳过（避免并发管道）。返回本次触发重新向量化的文档数。
     */
    public int reprocessAll(Long kbId) {
        kbService.requireKb(kbId);
        List<KbDocument> docs = documentMapper.selectList(new QueryWrapper<KbDocument>()
                        .eq("kb_id", kbId)
                        .ne("status", Constants.DOC_PARSING))
                .stream().filter(access::canRead).toList();
        int triggered = 0;
        for (KbDocument doc : docs) {
            if (!access.canRead(doc)) {
                continue;
            }
            doc.setStatus(Constants.DOC_PARSING);
            documentMapper.updateById(doc);
            pipeline.ingest(doc.getId());
            triggered++;
        }
        return triggered;
    }

    /** 修改文档权限/所属部门（P1）：更新行后异步重向量化，使 Milvus metadata 生效。 */
    @Transactional
    public void updatePermission(Long id, DocumentPermissionRequest req) {
        KbDocument doc = requireDoc(id);
        requireManage(doc);
        access.requireManage(req.departmentId());
        if (Constants.DOC_PARSING.equals(doc.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文档正在处理中，请稍后再试");
        }
        if (req.permissionLevel() == null || req.permissionLevel().isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "permissionLevel 不能为空");
        }
        doc.setPermissionLevel(normalizeLevel(req.permissionLevel()));
        doc.setDepartmentId(req.departmentId());   // null = 全司
        doc.setStatus(Constants.DOC_PARSING);
        milvus.deleteByDocumentId(id);
        documentMapper.updateById(doc);
        // 必须等待事务提交，避免异步线程读到旧权限；检索还会复核当前数据库权限。
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { pipeline.ingest(id); }
                });
    }

    private void requireManage(KbDocument doc) {
        access.requireManage(kbService.requireKb(doc.getKbId()).getDepartmentId());
        access.requireManage(doc.getDepartmentId());
    }

    private String normalizeLevel(String level) {
        String up = level.trim().toUpperCase();
        if (!up.equals(Constants.LV_PUBLIC) && !up.equals(Constants.LV_DEPT) && !up.equals(Constants.LV_CONFIDENTIAL)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "permissionLevel 仅支持 PUBLIC/DEPT/CONFIDENTIAL");
        }
        return up;
    }

    private String extensionOf(String filename) {
        int idx = filename.lastIndexOf('.');
        return idx < 0 ? "" : filename.substring(idx + 1).toLowerCase();
    }

    private DocumentVO toVO(KbDocument d) {
        return new DocumentVO(d.getId(), d.getKbId(), d.getFilename(), d.getFileType(), d.getSize(),
                d.getStatus(), d.getChunkCount(), d.getPermissionLevel(), d.getCreatedAt());
    }
}
