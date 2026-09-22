<script setup lang="ts">
// 知识库列表页（模块② P0）：列表 / 新建 / 删除，接 /api/kb
import { ref, reactive, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  listKnowledgeBases,
  createKnowledgeBase,
  deleteKnowledgeBase,
  reprocessAllDocuments,
  listDepartments,
  type KnowledgeBase,
  type Department,
  type PermissionLevel
} from '@/api/kb'

const router = useRouter()
const userStore = useUserStore()
const canManage = (departmentId?: number | null) => userStore.isSysAdmin ||
  (userStore.isAdmin && departmentId != null && departmentId === userStore.userInfo?.departmentId)

const list = ref<KnowledgeBase[]>([])
const loading = ref(false)
const nameKeyword = ref('')
const departments = ref<Department[]>([])

const filteredList = computed(() => {
  const keyword = nameKeyword.value.trim().toLowerCase()
  return list.value.filter((kb) =>
    !keyword || (kb.name ?? '').toLowerCase().includes(keyword)
  )
})

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

function departmentName(id?: number | null) {
  return departments.value.find((d) => d.id === id)?.name || (id ? `#${id}` : '全司')
}

async function fetchList() {
  loading.value = true
  try {
    list.value = await listKnowledgeBases()
  } finally {
    loading.value = false
  }
}

async function fetchDepartments() {
  try {
    departments.value = await listDepartments()
  } catch {
    // 部门接口暂不可用时，新建表单仅填名称+权限级别
  }
}

/* ---------------- 新建知识库 ---------------- */
const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<{
  name: string
  description: string
  departmentId: number | null
  permissionLevel: PermissionLevel
}>({
  name: '',
  description: '',
  departmentId: null,
  permissionLevel: 'PUBLIC'
})
const rules: FormRules = {
  name: [{ required: true, message: '请输入知识库名称', trigger: 'blur' }]
}

function openCreate() {
  form.name = ''
  form.description = ''
  form.departmentId = userStore.isSysAdmin ? null : (userStore.userInfo?.departmentId ?? null)
  form.permissionLevel = 'PUBLIC'
  dialogVisible.value = true
}

async function handleCreate() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await createKnowledgeBase({
      name: form.name,
      description: form.description || undefined,
      departmentId: form.departmentId,
      permissionLevel: form.permissionLevel
    })
    ElMessage.success('创建成功')
    dialogVisible.value = false
    await fetchList()
  } finally {
    submitting.value = false
  }
}

/* ---------------- 删除知识库 ---------------- */
async function handleDelete(kb: KnowledgeBase) {
  try {
    await ElMessageBox.confirm(
      `确定删除知识库「${kb.name}」吗？其下所有文档与向量数据将一并删除，且不可恢复。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await deleteKnowledgeBase(kb.id)
  ElMessage.success('删除成功')
  await fetchList()
}

function goDocuments(kb: KnowledgeBase) {
  router.push({ path: `/kb/${kb.id}/documents`, query: { name: kb.name } })
}

/* ---------------- 一键重新向量化知识库 ---------------- */
async function handleReprocessAll(kb: KnowledgeBase) {
  try {
    await ElMessageBox.confirm(
      `确定重新向量化「${kb.name}」下的全部文档吗？正在解析(PARSING)的会跳过，其余将重新解析分块并生成新向量。`,
      '重新向量化确认',
      { type: 'warning', confirmButtonText: '重新向量化', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  const triggered = await reprocessAllDocuments(kb.id)
  ElMessage.success(triggered > 0 ? `已触发 ${triggered} 个文档重新向量化` : '没有需要重新向量化的文档')
}

onMounted(() => {
  fetchList()
  fetchDepartments()
})
</script>

<template>
  <div class="kb-page">
    <el-card shadow="never">
      <template #header>
        <div class="kb-header">
          <span class="kb-title">知识库管理</span>
          <el-input v-model="nameKeyword" clearable placeholder="搜索知识库名称" style="width: 180px" />
          <el-button v-if="userStore.isAdmin" type="primary" @click="openCreate">新建知识库</el-button>
        </div>
      </template>

      <el-table v-loading="loading" :data="filteredList" stripe>
        <el-table-column prop="name" label="名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="kb-desc">{{ row.description || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="所属部门" width="120">
          <template #default="{ row }">{{ departmentName(row.departmentId) }}</template>
        </el-table-column>
        <el-table-column label="权限级别" width="110">
          <template #default="{ row }">
            <el-tag :type="permissionTagType(row.permissionLevel)" size="small">
              {{ permissionLabel(row.permissionLevel) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="goDocuments(row)">文档管理</el-button>
            <el-button
              v-if="canManage(row.departmentId)"
              link
              type="warning"
              @click="handleReprocessAll(row)"
            >重新向量化</el-button>
            <el-button v-if="canManage(row.departmentId)" link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无知识库，点击右上角新建" />
        </template>
      </el-table>
    </el-card>

    <!-- 新建知识库 -->
    <el-dialog v-model="dialogVisible" title="新建知识库" width="480px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入知识库名称" maxlength="128" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="2"
            placeholder="知识库用途说明（可选）"
            maxlength="255"
          />
        </el-form-item>
        <el-form-item label="所属部门">
          <el-select v-model="form.departmentId" :disabled="!userStore.isSysAdmin" placeholder="不选则为全公司" clearable style="width: 100%">
            <el-option
              v-for="d in departments"
              :key="d.id"
              :label="d.name"
              :value="d.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="权限级别">
          <el-select v-model="form.permissionLevel" style="width: 100%">
            <el-option
              v-for="o in permissionOptions"
              :key="o.value"
              :label="o.label"
              :value="o.value"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleCreate">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.kb-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.kb-title {
  font-weight: 600;
}
.kb-desc {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
