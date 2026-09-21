package com.corpedia.controller;

import com.corpedia.common.Result;
import com.corpedia.dto.request.DocumentPermissionRequest;
import com.corpedia.dto.response.DocumentChunkVO;
import com.corpedia.dto.response.DocumentVO;
import com.corpedia.dto.response.ProcessResultVO;
import com.corpedia.common.Constants;
import com.corpedia.security.UserContext;
import com.corpedia.security.UserContextHolder;
import com.corpedia.service.DocumentService;
import com.corpedia.service.PermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "文档管理", description = "文档上传 / 列表 / 详情 / 删除 / 状态查询 / chunk预览 / 重向量化 / 权限修改")
@RestController
@RequestMapping
public class DocumentController {

    private final DocumentService documentService;
    private final PermissionService permissionService;

    public DocumentController(DocumentService documentService, PermissionService permissionService) {
        this.documentService = documentService;
        this.permissionService = permissionService;
    }

    /** 上传文档（multipart，字段 file），立即返回 status=PARSING。 */
    @Operation(summary = "上传文档", description = "multipart 上传（字段名 file），支持 md/pdf/docx/txt；异步入库，立即返回 status=PARSING")
    @PostMapping("/kb/{kbId}/documents")
    public Result<DocumentVO> upload(@Parameter(description = "知识库 id") @PathVariable Long kbId,
                                     @Parameter(description = "文档文件") @RequestParam("file") MultipartFile file) {
        UserContext ctx = UserContextHolder.get();
        return Result.ok(documentService.upload(kbId, file, ctx == null ? null : ctx.userId()));
    }

    /** 知识库下文档列表。 */
    @Operation(summary = "知识库文档列表", description = "返回指定知识库下的全部文档")
    @GetMapping("/kb/{kbId}/documents")
    public Result<List<DocumentVO>> listByKb(@Parameter(description = "知识库 id") @PathVariable Long kbId) {
        return Result.ok(documentService.listByKb(kbId));
    }

    /** 文档详情（轮询端点，硬骨头3）：返回当前 status/chunkCount。 */
    @Operation(summary = "文档详情", description = "入库轮询端点：返回当前 status(PARSING/READY/FAILED) 与 chunkCount")
    @GetMapping("/documents/{id}")
    public Result<DocumentVO> detail(@Parameter(description = "文档 id") @PathVariable Long id) {
        return Result.ok(documentService.getDetail(id));
    }

    /** 批量状态查询（可选）：GET /api/documents?status=PARSING[&kbId=1]。 */
    @Operation(summary = "按状态查询文档", description = "可选按 status(PARSING/READY/FAILED) 与 kbId 过滤")
    @GetMapping("/documents")
    public Result<List<DocumentVO>> listByStatus(@Parameter(description = "文档状态") @RequestParam(required = false) String status,
                                                 @Parameter(description = "知识库 id") @RequestParam(required = false) Long kbId) {
        return Result.ok(documentService.listByStatus(status, kbId));
    }

    /** 删除文档：级联清 Milvus chunk 与磁盘文件。 */
    @Operation(summary = "删除文档", description = "级联清理 Milvus 向量与磁盘文件")
    @DeleteMapping("/documents/{id}")
    public Result<Void> delete(@Parameter(description = "文档 id") @PathVariable Long id) {
        documentService.delete(id);
        return Result.ok();
    }

    /* ---------------- 阶段4 P1 ---------------- */

    /** 文档分块预览（来源卡片/KB 详情抽屉数据源）。 */
    @Operation(summary = "文档分块列表", description = "按 chunk_index 升序返回文档全部分块；similarity 无检索 query 时为 null")
    @GetMapping("/documents/{id}/chunks")
    public Result<List<DocumentChunkVO>> chunks(@Parameter(description = "文档 id") @PathVariable Long id) {
        return Result.ok(documentService.listChunks(id));
    }

    /** 重新向量化（SYS_ADMIN / DEPT_ADMIN）。 */
    @Operation(summary = "重新向量化", description = "对 READY/FAILED 文档重新解析分块嵌入（PARSING 中拒绝）")
    @PostMapping("/documents/{id}/reprocess")
    public Result<ProcessResultVO> reprocess(@Parameter(description = "文档 id") @PathVariable Long id) {
        permissionService.requireDocManage(UserContextHolder.get());
        documentService.reprocess(id);
        return Result.ok(new ProcessResultVO(id, Constants.DOC_PARSING));
    }

    /** 一键重新向量化（SYS_ADMIN / DEPT_ADMIN）：对知识库下所有 READY/FAILED 文档重新入库，PARSING 跳过。 */
    @Operation(summary = "一键重新向量化知识库", description = "对指定知识库下所有 READY/FAILED 文档重新解析分块嵌入（PARSING 中的跳过）")
    @PostMapping("/kb/{kbId}/documents/reprocess-all")
    public Result<Integer> reprocessAll(@Parameter(description = "知识库 id") @PathVariable Long kbId) {
        permissionService.requireDocManage(UserContextHolder.get());
        int triggered = documentService.reprocessAll(kbId);
        return Result.ok(triggered);
    }

    /** 修改文档权限/所属部门（SYS_ADMIN / DEPT_ADMIN），异步重向量化生效。 */
    @Operation(summary = "修改文档权限/所属部门", description = "更新后异步重新向量化；body: { permissionLevel, departmentId? }")
    @PutMapping("/documents/{id}/permission")
    public Result<Void> permission(@Parameter(description = "文档 id") @PathVariable Long id,
                                   @RequestBody DocumentPermissionRequest req) {
        permissionService.requireDocManage(UserContextHolder.get());
        documentService.updatePermission(id, req);
        return Result.ok();
    }
}
