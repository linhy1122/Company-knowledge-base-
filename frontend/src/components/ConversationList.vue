<script setup lang="ts">
// 会话侧边栏（模块⑥⑦ 增强）：新建 / 切换 / 重命名 / 续接 / 归档 / 删除 + scope 筛选
import { ref } from 'vue'
import { ChatDotRound, Delete, Plus, EditPen, CopyDocument, FolderOpened, Folder } from '@element-plus/icons-vue'
import type { Conversation, ConvScope } from '@/api/conversation'

defineProps<{
  items: Conversation[]
  currentId: number | null
  loading?: boolean
  scope?: ConvScope
}>()

const emit = defineEmits<{
  create: []
  select: [id: number]
  delete: [id: number]
  rename: [id: number, title: string]
  fork: [id: number]
  archive: [id: number, archived: boolean]
  'update:scope': [scope: ConvScope]
}>()

// inline 重命名
const editingId = ref<number | null>(null)
const editTitle = ref('')

function startRename(item: Conversation) {
  editingId.value = item.id
  editTitle.value = item.title || ''
}
function commitRename(item: Conversation) {
  const title = editTitle.value.trim()
  editingId.value = null
  if (title && title !== (item.title || '')) {
    emit('rename', item.id, title)
  }
}
</script>

<template>
  <div class="conv-panel">
    <div class="conv-header">
      <span class="conv-title">会话</span>
      <el-button type="primary" :icon="Plus" size="small" @click="emit('create')">新建会话</el-button>
    </div>

    <!-- scope 切换 -->
    <el-radio-group
      class="conv-scope"
      :model-value="scope || 'active'"
      size="small"
      @update:model-value="(s: ConvScope) => emit('update:scope', s)"
    >
      <el-radio-button value="active">进行中</el-radio-button>
      <el-radio-button value="all">全部</el-radio-button>
      <el-radio-button value="archived">已归档</el-radio-button>
    </el-radio-group>

    <div v-loading="loading" class="conv-list">
      <el-empty
        v-if="!loading && items.length === 0"
        :image-size="60"
        description="暂无会话，点击「新建会话」开始提问"
      />
      <div
        v-for="item in items"
        :key="item.id"
        class="conv-item"
        :class="{ active: item.id === currentId, archived: item.archived }"
        @click="emit('select', item.id)"
      >
        <el-icon class="conv-item-icon"><ChatDotRound /></el-icon>

        <template v-if="editingId === item.id">
          <el-input
            v-model="editTitle"
            size="small"
            class="conv-item-edit"
            autofocus
            maxlength="50"
            @click.stop
            @blur="commitRename(item)"
            @keyup.enter="commitRename(item)"
            @keyup.esc="editingId = null"
          />
        </template>
        <template v-else>
          <span class="conv-item-title" :title="item.title || '新会话'">
            {{ item.title || '新会话' }}
          </span>
          <el-tag v-if="item.archived" size="small" type="info" class="conv-item-tag">已归档</el-tag>
        </template>

        <div class="conv-item-actions" @click.stop>
          <el-tooltip content="重命名">
            <el-icon class="conv-item-act" @click="startRename(item)"><EditPen /></el-icon>
          </el-tooltip>
          <el-tooltip content="续接（复制为新会话）">
            <el-icon class="conv-item-act" @click="emit('fork', item.id)"><CopyDocument /></el-icon>
          </el-tooltip>
          <el-tooltip :content="item.archived ? '取消归档' : '归档'">
            <el-icon v-if="item.archived" class="conv-item-act" @click="emit('archive', item.id, false)">
              <Folder />
            </el-icon>
            <el-icon v-else class="conv-item-act" @click="emit('archive', item.id, true)">
              <FolderOpened />
            </el-icon>
          </el-tooltip>
          <el-tooltip content="删除">
            <el-icon class="conv-item-act conv-item-delete" @click="emit('delete', item.id)"><Delete /></el-icon>
          </el-tooltip>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.conv-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  border-right: 1px solid var(--el-border-color-light);
  background: var(--el-bg-color);
}
.conv-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.conv-title {
  font-size: 15px;
  font-weight: 600;
}
.conv-scope {
  display: flex;
  padding: 8px;
}
.conv-scope .el-radio-button {
  flex: 1;
}
.conv-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}
.conv-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 10px;
  border-radius: 6px;
  cursor: pointer;
  margin-bottom: 4px;
  color: var(--el-text-color-primary);
  transition: background 0.2s;
}
.conv-item:hover {
  background: var(--el-fill-color-light);
}
.conv-item.active {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}
.conv-item.archived {
  color: var(--el-text-color-secondary);
}
.conv-item-icon {
  flex: none;
  font-size: 15px;
}
.conv-item-title {
  flex: 1;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.conv-item-edit {
  flex: 1;
}
.conv-item-tag {
  flex: none;
  margin-left: 2px;
}
.conv-item-actions {
  flex: none;
  display: none;
  align-items: center;
  gap: 6px;
}
.conv-item:hover .conv-item-actions {
  display: flex;
}
.conv-item-act {
  font-size: 14px;
  color: var(--el-text-color-secondary);
}
.conv-item-act:hover {
  color: var(--el-color-primary);
}
.conv-item-delete:hover {
  color: var(--el-color-danger);
}
</style>