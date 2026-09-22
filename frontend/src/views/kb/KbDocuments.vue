<script setup lang="ts">
// 文档管理页（模块② P0 + P1）：文档列表/状态筛选/拖拽上传+进度/状态轮询/删除确认
// P1：chunk 预览、重新向量化、权限/部门设置
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadRequestOptions } from 'element-plus'
import { UploadFilled, ArrowLeft } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import {
  listKnowledgeBases,
  listDocuments,
  getDocument,
  uploadDocument,
  deleteDocument,
  getDocumentChunks,
  reprocessDocument,
  reprocessAllDocuments,
  updateDocumentPermission,
  listDepartments,
  type DocumentItem,
  type DocumentChunk,
  type DocumentStatus,
  type Department,
  type PermissionLevel
} from '@/api/kb'
import { renderMarkdown } from '@/utils/markdown'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const kbId = Number(route.params.id)
const kbName = ref((route.query.name as string) || `知识库 #${kbId}`)
const kbDepartmentId = ref<number | null>(null)
const canManage = computed(() => userStore.isSysAdmin || (userStore.isAdmin &&
  kbDepartmentId.value != null && kbDepartmentId.value === userStore.userInfo?.departmentId))

const MAX_MB = Number(import.meta.env.VITE_UPLOAD_MAX_MB || 10)
const MAX_SIZE = MAX_MB * 1024 * 1024

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))

/* ---------------- 列表与筛选 ---------------- */
const documents = ref<DocumentItem[]>([])
const loading = ref(false)
const statusFilter = ref<'' | DocumentStatus>('')
const filenameKeyword = ref('')

const filteredDocs = computed(() => {
  const keyword = filenameKeyword.value.trim().toLowerCase()
  return documents.value.filter((doc) =>
    (!statusFilter.value || doc.status === statusFilter.value) &&
    (!keyword || (doc.filename ?? '').toLowerCase().includes(keyword))
  )
})

const statusMap: Record<DocumentStatus, { label: string; type: 'success' | 'warning' | 'danger' }> = {
  PARSING: { label: '解析中', type: 'warning' },
  READY: { label: '已就绪', type: 'success' },
  FAILED: { label: '失败', type: 'danger' }
}

const permissionOptions: { label: string; value: PermissionLevel }[] = [
  { label: '公开', value: 'PUBLIC' },
  { label: '部门', value: 'DEPT' },
  { label: '机密', value: 'CONFIDENTIAL' }
]

function permissionLabel(v?: PermissionLevel | string) {
  return permissionOptions.find((o) => o.value === v)?.label || v || '-'
}
function permissionTagType(v?: PermissionLevel | string) {
  if (v === 'CONFIDENTIAL') return 'danger'
  if (v === 'DEPT') return 'warning'
  return 'info'
}
function formatSize(bytes: number) {
  if (bytes == null) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

function updateRow(doc: DocumentItem) {
  const idx = documents.value.findIndex((d) => d.id === doc.id)
  if (idx >= 0) documents.value[idx] = doc
}

/* ---------------- 拉取与自动刷新（PARSING 中每 3s 静默刷新） ---------------- */
async function fetchDocuments(showLoading = true) {
  if (showLoading) loading.value = true
  try {
    documents.value = await listDocuments(kbId)
    syncAutoRefresh()
  } finally {
    if (showLoading) loading.value = false
  }
}

let refreshTimer: number | undefined
function hasParsingDocs() {
  return documents.value.some((d) => d.status === 'PARSING')
}
function stopAutoRefresh() {
  if (refreshTimer) {
    window.clearInterval(refreshTimer)
    refreshTimer = undefined
  }
}
function syncAutoRefresh() {
  if (hasParsingDocs()) {
    if (!refreshTimer) {
      refreshTimer = window.setInterval(() => fetchDocuments(false), 3000)
    }
  } else {
    stopAutoRefresh()
  }
}

/* ---------------- 状态轮询（上传/重向量化后，每 2.5s 一次，3 分钟超时） ---------------- */
async function pollDocument(docId: number, timeoutMs = 3 * 60 * 1000): Promise<DocumentItem> {
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    try {
      const doc = await getDocument(docId)
      updateRow(doc)
      if (doc.status !== 'PARSING') return doc
    } catch {
      // 单次轮询失败则等待下一次
    }
    await sleep(2500)
  }
  throw new Error('文档处理超时')
}

/* ---------------- 上传（拖拽 + 进度条） ---------------- */
const uploading = ref(false)
const uploadPercent = ref(0)
const uploadFileName = ref('')

function beforeUpload(file: File) {
  if (file.size > MAX_SIZE) {
    ElMessage.error(`单个文件不能超过 ${MAX_MB}MB`)
    return false
  }
  return true
}

async function customUpload(options: UploadRequestOptions) {
  const form = new FormData()
  form.append('file', options.file)
  uploading.value = true
  uploadFileName.value = options.file.name
  uploadPercent.value = 0
  try {
    const doc = await uploadDocument(kbId, form, (e) => {
      const total = e.total || 0
      uploadPercent.value = total ? Math.round((e.loaded / total) * 100) : 0
    })
    ElMessage.success('上传成功，正在解析文档')
    documents.value.unshift(doc)
    try {
      const final = await pollDocument(doc.id)
      ElMessage.success(
        final.status === 'READY' ? `「${final.filename}」解析完成` : `「${final.filename}」解析失败`
      )
    } catch {
      ElMessage.warning('文档处理超时，请稍后刷新查看状态')
    }
    await fetchDocuments(false)
  } catch {
    // 错误提示已由拦截器统一处理
  } finally {
    uploading.value = false
  }
}

/* ---------------- 删除（二次确认） ---------------- */
async function handleDelete(doc: DocumentItem) {
  try {
    await ElMessageBox.confirm(
      `确定删除文档「${doc.filename}」吗？其向量数据将一并清除。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await deleteDocument(doc.id)
  ElMessage.success('删除成功')
  await fetchDocuments()
}

/* ---------------- P1：chunk 预览 ---------------- */
const drawerVisible = ref(false)
const drawerLoading = ref(false)
const chunks = ref<DocumentChunk[]>([])
const currentDoc = ref<DocumentItem | null>(null)

async function openDetail(doc: DocumentItem) {
  currentDoc.value = doc
  chunks.value = []
  drawerVisible.value = true
  drawerLoading.value = true
  try {
    chunks.value = await getDocumentChunks(doc.id)
  } catch {
    chunks.value = []
  } finally {
    drawerLoading.value = false
  }
}

/* ---------------- P1：重新向量化 ---------------- */
async function handleReprocess(doc: DocumentItem) {
  try {
    await ElMessageBox.confirm(
      `确定对「${doc.filename}」重新向量化吗？处理期间该文档不可用于检索。`,
      '重新向量化',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await reprocessDocument(doc.id)
  ElMessage.success('已触发重新向量化')
  updateRow({ ...doc, status: 'PARSING' })
  try {
    const final = await pollDocument(doc.id)
    ElMessage.success(final.status === 'READY' ? '重新向量化完成' : '重新向量化失败')
  } catch {
    ElMessage.warning('处理超时，请稍后刷新查看状态')
  }
  await fetchDocuments(false)
}

/* ---------------- P1：一键重新向量化全部 ---------------- */
async function handleReprocessAll() {
  try {
    await ElMessageBox.confirm(
      `确定对当前知识库下所有已导入文档重新向量化吗？`,
      '一键重新向量化',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  const triggered = await reprocessAllDocuments(kbId)
  ElMessage.success(triggered > 0 ? `已触发 ${triggered} 篇文档重新向量化` : '没有需要重新向量化的文档')
  if (triggered > 0) {
    await fetchDocuments(false)
    ElMessage.info('处理需异步完成，请稍后刷新查看最新状态')
  }
}

/* ---------------- P1：权限/部门设置 ---------------- */
const departments = ref<Department[]>([])
const permDialogVisible = ref(false)
const permSubmitting = ref(false)
const permForm = reactive<{ permissionLevel: PermissionLevel; departmentId: number | null }>({
  permissionLevel: 'PUBLIC',
  departmentId: null
})

async function openPermission(doc: DocumentItem) {
  currentDoc.value = doc
  permForm.permissionLevel = doc.permissionLevel || 'PUBLIC'
  permForm.departmentId = null
  if (departments.value.length === 0) {
    try {
      departments.value = await listDepartments()
    } catch {
      // 部门接口暂不可用时，仅设置权限级别
    }
  }
  permDialogVisible.value = true
}

async function submitPermission() {
  if (!currentDoc.value) return
  permSubmitting.value = true
  try {
    await updateDocumentPermission(currentDoc.value.id, {
      permissionLevel: permForm.permissionLevel,
      departmentId: permForm.departmentId
    })
    ElMessage.success('权限已更新')
    permDialogVisible.value = false
    await fetchDocuments(false)
  } finally {
    permSubmitting.value = false
  }
}

function goBack() {
  router.push('/kb')
}

onMounted(async () => {
  await fetchDocuments()
  // 刷新页面后从路由 query 拿不到名称时，回查知识库列表补全标题
  {
    try {
      const kbs = await listKnowledgeBases()
      kbName.value = kbs.find((k) => k.id === kbId)?.name || `知识库 #${kbId}`
      kbDepartmentId.value = kbs.find((k) => k.id === kbId)?.departmentId ?? null
    } catch {
      // 保持默认标题
    }
  }
})

onUnmounted(stopAutoRefresh)
</script>

<template>
  <div class="doc-page">
    <el-card shadow="never">
      <template #header>
        <div class="doc-header">
          <div class="doc-header-left">
            <el-button link type="primary" :icon="ArrowLeft" @click="goBack">返回</el-button>
            <span class="doc-title">{{ kbName }} · 文档管理</span>
          </div>
          <div class="doc-header-right">
            <el-input v-model="filenameKeyword" clearable placeholder="搜索文件名" style="width: 180px" />
            <el-radio-group v-model="statusFilter" size="small">
              <el-radio-button value="">全部</el-radio-button>
              <el-radio-button value="PARSING">解析中</el-radio-button>
              <el-radio-button value="READY">已就绪</el-radio-button>
              <el-radio-button value="FAILED">失败</el-radio-button>
            </el-radio-group>
            <el-button
              v-if="canManage"
              type="primary"
              plain
              size="small"
              @click="handleReprocessAll"
            >
              全部重新向量化
            </el-button>
          </div>
        </div>
      </template>

      <!-- 拖拽上传 -->
      <div v-if="canManage" class="upload-area">
        <el-upload
          drag
          multiple
          :show-file-list="false"
          :disabled="uploading"
          :http-request="customUpload"
          :before-upload="beforeUpload"
        >
          <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
          <div class="el-upload__text">将文件拖到此处，或 <em>点击上传</em></div>
          <template #tip>
            <div class="el-upload__tip">支持 md / pdf / docx / txt，单个文件不超过 {{ MAX_MB }}MB</div>
          </template>
        </el-upload>
        <div v-if="uploading" class="upload-progress">
          <span class="upload-filename">{{ uploadFileName }}</span>
          <el-progress :percentage="uploadPercent" :stroke-width="10" />
        </div>
      </div>

      <!-- 文档列表 -->
      <el-table v-loading="loading" :data="filteredDocs" stripe>
        <el-table-column prop="filename" label="文件名" min-width="220" show-overflow-tooltip />
        <el-table-column prop="fileType" label="类型" width="80" />
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ formatSize(row.size) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusMap[row.status as DocumentStatus]?.type" size="small">
              {{ statusMap[row.status as DocumentStatus]?.label || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="chunkCount" label="分块数" width="90" />
        <el-table-column label="权限" width="90">
          <template #default="{ row }">
            <el-tag :type="permissionTagType(row.permissionLevel)" size="small" effect="plain">
              {{ permissionLabel(row.permissionLevel) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="上传时间" width="170" />
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :disabled="row.status !== 'READY'"
              @click="openDetail(row)"
            >
              详情
            </el-button>
            <el-button
              v-if="canManage"
              link
              type="primary"
              :disabled="row.status === 'PARSING'"
              @click="handleReprocess(row)"
            >
              重新向量化
            </el-button>
            <el-button v-if="canManage" link type="warning" @click="openPermission(row)">权限设置</el-button>
            <el-button v-if="canManage" link type="danger" :disabled="row.status === 'PARSING'" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="statusFilter ? '当前筛选条件下无文档' : '暂无文档，请上传'" />
        </template>
      </el-table>
    </el-card>

    <!-- P1：chunk 预览抽屉 -->
    <el-drawer v-model="drawerVisible" size="46%" :title="`${currentDoc?.filename || '文档'} · 分块预览`">
      <div v-loading="drawerLoading" class="chunk-list">
        <el-empty v-if="!drawerLoading && chunks.length === 0" description="暂无分块数据" />
        <div v-for="c in chunks" :key="c.chunkIndex" class="chunk-item">
          <div class="chunk-meta">
            <el-tag size="small" effect="plain">#{{ c.chunkIndex }}</el-tag>
            <span class="chunk-similarity">相似度 {{ c.similarity }}</span>
          </div>
          <div class="chunk-content md-render" v-html="renderMarkdown(c.content)"></div>
        </div>
      </div>
    </el-drawer>

    <!-- P1：权限/部门设置 -->
    <el-dialog v-model="permDialogVisible" title="权限设置" width="440px" destroy-on-close>
      <el-form :model="permForm" label-width="90px">
        <el-form-item label="权限级别">
          <el-select v-model="permForm.permissionLevel" style="width: 100%">
            <el-option
              v-for="o in permissionOptions"
              :key="o.value"
              :label="o.label"
              :value="o.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="所属部门">
          <el-select
            v-model="permForm.departmentId"
            placeholder="不选则为全司"
            clearable
            style="width: 100%"
          >
            <el-option v-for="d in departments" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="permDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="permSubmitting" @click="submitPermission">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.doc-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.doc-header-left {
  display: flex;
  align-items: center;
  gap: 8px;
}
.doc-header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.doc-title {
  font-weight: 600;
}
.upload-area {
  margin-bottom: 16px;
}
.upload-progress {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 10px;
  padding: 0 4px;
}
.upload-filename {
  min-width: 140px;
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.chunk-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.chunk-item {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 10px 12px;
}
.chunk-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.chunk-similarity {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.chunk-content {
  font-size: 13px;
  line-height: 1.7;
  color: var(--el-text-color-primary);
}
/* markdown 渲染排版见全局 styles/markdown.css (.md-render) */
.perm-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
  margin-top: 4px;
}
</style>
