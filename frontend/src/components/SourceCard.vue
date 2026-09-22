<script setup lang="ts">
// 来源片段卡片（模块④⑤）：展示 document_id/title/chunk/相似度，点击预览来源
// 功能扩展01：优先拉取原文上下文（前/高亮/后三段 + 高亮定位），cleaned_text 未就绪降级为分块预览
import { ref } from 'vue'
import { getDocumentChunks, getDocumentContext } from '@/api/kb'
import type { Source } from '@/api/conversation'
import { renderMarkdown } from '@/utils/markdown'

/** 渲染为内联：去掉 markdown-it 的块级 <p> 外层包裹，避免把 <p> 塞进行内 <mark>（非法嵌套致高亮消失） */
function renderInline(mdText: string): string {
  let html = renderMarkdown(mdText).trim()
  if (html.startsWith('<p>') && html.endsWith('</p>')) {
    html = html.slice(3, -4)
  }
  return html
}

const props = defineProps<{ source: Source }>()

const previewVisible = ref(false)
const previewLoading = ref(false)
const chunkContent = ref('')
const contentUnavailable = ref(false)
const contextReady = ref(false)
const contextBefore = ref('')
const contextHighlight = ref('')
const contextAfter = ref('')
const contextUnavailable = ref(false)
/** 弹窗工具栏提示：chunk 在原文中的字符区间 */
const chunkRange = ref('')

/** 从 chunkId（后端格式 doc-{documentId}-{index}）解析分块序号 */
function chunkIndexFromId(chunkId: string): number | null {
  const parts = chunkId.split('-')
  const last = Number(parts[parts.length - 1])
  return parts.length >= 3 && Number.isFinite(last) ? last : null
}

/** 降级：按 chunkIndex 拉取并展示单个分块文本 */
async function fallbackToChunk() {
  const chunks = await getDocumentChunks(props.source.documentId, { silent: true })
  const idx = chunkIndexFromId(props.source.chunkId)
  const hit = idx != null ? chunks[idx] : undefined
  if (hit) {
    chunkContent.value = hit.content
  } else {
    contentUnavailable.value = true
  }
}

async function openPreview() {
  previewVisible.value = true
  previewLoading.value = true
  chunkContent.value = ''
  contentUnavailable.value = false
  contextReady.value = false
  contextBefore.value = ''
  contextHighlight.value = ''
  contextAfter.value = ''
  contextUnavailable.value = false
  chunkRange.value = ''
  try {
    const s = props.source
    if (s.chunkStart != null && s.chunkEnd != null) {
      chunkRange.value = `chunk @[${s.chunkStart}, ${s.chunkEnd})`
      // 优先走原文上下文接口（引用溯源高亮）
      const ctx = await getDocumentContext(s.documentId, { chunkStart: s.chunkStart, chunkEnd: s.chunkEnd })
      contextReady.value = true
      contextBefore.value = ctx.before
      contextHighlight.value = ctx.highlight
      contextAfter.value = ctx.after
    } else {
      // 旧消息/旧数据无区间 → 直接分块预览
      await fallbackToChunk()
    }
  } catch {
    // cleaned_text 未就绪（400）或接口异常 → 降级为分块预览并提示
    contextUnavailable.value = true
    try {
      await fallbackToChunk()
    } catch {
      contentUnavailable.value = true
    }
  } finally {
    previewLoading.value = false
  }
}
</script>

<template>
  <div class="source-card" title="点击查看来源分块" @click="openPreview">
    <div class="source-title" :title="source.title || '未命名文档'">
      {{ source.title || '未命名文档' }}
    </div>
    <div class="source-meta">
      <span class="source-id">文档 #{{ source.documentId }}</span>
      <el-tag size="small" type="info" effect="plain">相似度 {{ source.similarity.toFixed(2) }}</el-tag>
    </div>

    <el-dialog
      v-model="previewVisible"
      width="560px"
      :title="`来源预览 · ${source.title || '未命名文档'}`"
      append-to-body
    >
      <div v-loading="previewLoading" class="preview-body">
        <div class="preview-meta">
          <span>文档 ID：{{ source.documentId }}</span>
          <span>分块：{{ source.chunkId }}</span>
          <span v-if="chunkRange" class="chunk-range">{{ chunkRange }}</span>
          <el-tag size="small" type="info" effect="plain">
            相似度 {{ source.similarity.toFixed(4) }}
          </el-tag>
        </div>
        <template v-if="!previewLoading">
          <div v-if="contentUnavailable" class="preview-empty">分块内容暂不可用</div>
          <template v-else-if="contextReady">
            <div class="preview-content md-render">
              <span v-if="contextBefore" v-html="renderInline(contextBefore)"></span
              ><mark class="context-hl" v-html="renderInline(contextHighlight)"></mark
              ><span v-if="contextAfter" v-html="renderInline(contextAfter)"></span>
            </div>
          </template>
          <div v-else class="preview-content md-render" v-html="renderMarkdown(chunkContent)"></div>
          <div v-if="contextUnavailable" class="preview-tip">
            原文尚未就绪，可在文档管理中重新向量化后查看上下文；当前展示该分块文本。
          </div>
        </template>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.source-card {
  display: inline-flex;
  flex-direction: column;
  gap: 4px;
  max-width: 260px;
  padding: 6px 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s;
}
.source-card:hover {
  border-color: var(--el-color-primary-light-5);
  background: var(--el-fill-color-light);
}
.source-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.source-meta {
  display: flex;
  align-items: center;
  gap: 8px;
}
.source-id {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.preview-body {
  min-height: 120px;
}
.preview-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 12px;
}
.preview-content {
  font-size: 13px;
  line-height: 1.8;
  padding: 12px;
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
}
/* 功能扩展01：命中分块高亮（原文上下文模式） */
.context-hl {
  background: #fff3b0;
  color: #333;
  border-radius: 2px;
  padding: 0 1px;
}
.context-hl :deep(*) {
  background: transparent;
}
.chunk-range {
  font-family: var(--el-font-family-mono, monospace);
}
.preview-tip {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-color-warning);
}
/* markdown 渲染排版见全局 styles/markdown.css (.md-render) */
.preview-empty {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  text-align: center;
  padding: 24px 0;
}
</style>
