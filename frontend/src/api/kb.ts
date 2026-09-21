import type { AxiosProgressEvent } from 'axios'
import { http } from '@/utils/request'

/** 权限级别：PUBLIC=公开 / DEPT=部门 / CONFIDENTIAL=机密（与后端 DDL 一致） */
export type PermissionLevel = 'PUBLIC' | 'DEPT' | 'CONFIDENTIAL'

/** 文档处理状态：PARSING=解析中 / READY=已就绪 / FAILED=失败 */
export type DocumentStatus = 'PARSING' | 'READY' | 'FAILED'

/** 知识库（GET/POST /api/kb 返回项） */
export interface KnowledgeBase {
  id: number
  name: string
  departmentId?: number | null
  permissionLevel: PermissionLevel
  description?: string
}

/** 文档（GET /api/kb/{kbId}/documents 返回项） */
export interface DocumentItem {
  id: number
  filename: string
  fileType: string
  size: number
  status: DocumentStatus
  chunkCount: number
  permissionLevel: PermissionLevel
  uploadedBy?: number | null
  createdAt: string
}

/** 文档分块（GET /api/documents/{id}/chunks 返回项） */
export interface DocumentChunk {
  chunkIndex: number
  content: string
  similarity: number
}

/** 部门（GET /api/departments 返回项） */
export interface Department {
  id: number
  name: string
  parentId?: number | null
  description?: string
}

/** 触发处理/重试的返回（POST /api/rag/process、/api/documents/{id}/reprocess） */
export interface ProcessResult {
  documentId: number
  status: DocumentStatus
}

/* ---------------- 知识库 ---------------- */

/** 知识库列表 */
export function listKnowledgeBases() {
  return http.get<KnowledgeBase[]>('/kb')
}

/** 新建知识库 */
export function createKnowledgeBase(data: {
  name: string
  description?: string
  departmentId?: number | null
  permissionLevel?: PermissionLevel
}) {
  return http.post<KnowledgeBase>('/kb', data)
}

/** 删除知识库（级联删除其下文档与向量数据） */
export function deleteKnowledgeBase(id: number) {
  return http.delete<void>(`/kb/${id}`)
}

/* ---------------- 文档 ---------------- */

/** 知识库下文档列表 */
export function listDocuments(kbId: number) {
  return http.get<DocumentItem[]>(`/kb/${kbId}/documents`)
}

/** 文档详情（上传后轮询状态用） */
export function getDocument(id: number) {
  return http.get<DocumentItem>(`/documents/${id}`)
}

/** 上传文档（multipart，字段名 file），支持上传进度回调 */
export function uploadDocument(
  kbId: number,
  formData: FormData,
  onProgress?: (e: AxiosProgressEvent) => void
) {
  return http.post<DocumentItem>(`/kb/${kbId}/documents`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    onUploadProgress: onProgress
  })
}

/** 删除文档（级联清除向量数据） */
export function deleteDocument(id: number) {
  return http.delete<void>(`/documents/${id}`)
}

/* ---------------- 文档扩展（P1） ---------------- */

/** 文档分块预览（P1 接口，未实现时调用方可传 { silent: true } 静默降级） */
export function getDocumentChunks(id: number, config?: { silent?: boolean }) {
  return http.get<DocumentChunk[]>(`/documents/${id}/chunks`, undefined, config)
}

/** 重新向量化 */
export function reprocessDocument(id: number) {
  return http.post<ProcessResult>(`/documents/${id}/reprocess`)
}

/** 一键重新向量化某知识库下所有已导入文档（返回触发数量） */
export function reprocessAllDocuments(kbId: number) {
  return http.post<number>(`/kb/${kbId}/documents/reprocess-all`)
}

/** 修改文档权限 / 所属部门 */
export function updateDocumentPermission(
  id: number,
  data: { permissionLevel: PermissionLevel; departmentId?: number | null }
) {
  return http.put<void>(`/documents/${id}/permission`, data)
}

/* ---------------- 部门（权限设置表单下拉用） ---------------- */

/** 部门列表 */
export function listDepartments() {
  return http.get<Department[]>('/departments')
}
