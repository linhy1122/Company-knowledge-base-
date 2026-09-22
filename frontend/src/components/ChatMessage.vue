<script setup lang="ts">
// 消息气泡（模块⑤⑥）：用户 / AI 消息、来源引用、拒答标识、赞/踩评价（P1）
import { computed, defineComponent, h, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CircleClose, Loading } from '@element-plus/icons-vue'
import SourceCard from '@/components/SourceCard.vue'
import { submitFeedback, type Source } from '@/api/conversation'
import { renderMarkdown } from '@/utils/markdown'

/** 会话内消息项（历史消息 + 本地即时渲染消息统一形态） */
export interface ChatMessageItem {
  /** 本地唯一标识（用于列表渲染 key 与占位替换） */
  uid: number
  id?: number | null
  role: 'USER' | 'ASSISTANT'
  content: string
  sources?: Source[] | null
  answered?: boolean | null
  createdAt?: string | null
  loading?: boolean
  error?: boolean
}

const props = defineProps<{ message: ChatMessageItem }>()

// 自定义赞/踩图标（Element Plus 无内置 thumb 图标，使用内联 SVG）
const ThumbUp = /* @__PURE__ */ defineComponent({
  name: 'ThumbUp',
  render: () =>
    h(
      'svg',
      {
        viewBox: '0 0 1024 1024',
        width: '16',
        height: '16',
        fill: 'currentColor',
        xmlns: 'http://www.w3.org/2000/svg'
      },
      h('path', {
        d: 'M858.24 442.88a57.6 57.6 0 0 1 41.14-16.64H896v307.2h-64c-21.76 0-39.68 8.96-57.6 16.64-24.96 11.52-49.92 22.4-74.88 22.4H520.96c-40.96 0-81.92-3.2-121.6 11.52l-34.56 13.44c-12.8 4.8-25.6 6.4-38.4 4.8V448h57.6c24.96 0 51.2-19.2 67.2-44.8 17.92-30.72 32-64 40.96-97.28 6.4-24.96 12.8-52.48 16.64-77.44 3.2-24.96 12.8-48 32-66.56 17.92-17.92 48-22.4 67.2 0 11.52 12.8 17.92 32 21.76 50.56 4.8 25.6 8.96 51.2 20.48 76.8a350.72 350.72 0 0 0 88.96 137.6zM435.2 832h320c30.72 0 60.8-6.4 89.6-21.12V409.6H691.2c-59.52-27.52-95.36-70.4-117.12-115.2-10.24-24.96-17.92-54.4-25.6-83.2-4.8-16.64-9.6-34.56-17.92-50.56-6.4-12.8-22.4-14.08-32-3.2-11.52 12.8-17.92 30.72-19.2 46.08-2.56 22.4-7.68 52.48-12.8 78.08-8.96 40.96-24.96 76.8-44.8 110.08-16.64 30.72-48 41.6-78.08 41.6h-20.48v331.48zM384 409.6h-38.4A64 64 0 0 0 281.6 473.6v300.8c0 35.2 28.8 64 64 64h38.4V409.6zM128 409.6h64v417.28h-64a32 32 0 0 1-32-32v-353.28c0-17.92 14.08-32 32-32z'
      })
    )
})
const ThumbDown = /* @__PURE__ */ defineComponent({
  name: 'ThumbDown',
  render: () =>
    h(
      'svg',
      {
        viewBox: '0 0 1024 1024',
        width: '16',
        height: '16',
        fill: 'currentColor',
        xmlns: 'http://www.w3.org/2000/svg'
      },
      h('path', {
        d: 'M858.24 581.12a57.6 57.6 0 0 1-41.14 16.64H896V582.4h0v-307.2h-64c-21.76 0-39.68-8.96-57.6-16.64-24.96-11.52-49.92-22.4-74.88-22.4H520.96c-40.96 0-81.92 3.2-121.6-11.52l-34.56-13.44c-12.8-4.8-25.6-6.4-38.4-4.8V576h57.6c24.96 0 51.2 19.2 67.2 44.8 17.92 30.72 32 64 40.96 97.28 6.4 24.96 12.8 52.48 16.64 77.44 3.2 24.96 12.8 48 32 66.56 17.92 17.92 48 22.4 67.2 0 11.52-12.8 17.92-32 21.76-50.56 4.8-25.6 8.96-51.2 20.48-76.8a350.72 350.72 0 0 0 88.96-137.6zM435.2 192h320c30.72 0 60.8 6.4 89.6 21.12v422.4H691.2c-59.52 27.52-95.36 70.4-117.12 115.2-10.24 24.96-17.92 54.4-25.6 83.2-4.8 16.64-9.6 34.56-17.92 50.56-6.4 12.8-22.4 14.08-32 3.2-11.52-12.8-17.92-30.72-19.2-46.08-2.56-22.4-7.68-52.48-12.8-78.08-8.96-40.96-24.96-76.8-44.8-110.08-16.64-30.72-48-41.6-78.08-41.6h-20.48V192zM384 614.4h-38.4A64 64 0 0 0 281.6 550.4V249.6c0-35.2 28.8-64 64-64h38.4v428.8zM128 614.4h64V197.12h-64a32 32 0 0 0-32 32v353.28c0 17.92 14.08 32 32 32z'
      })
    )
})

// 已评价记录：模块级共享，切换会话 / 重新渲染后仍保留本次会话内的评价状态
const ratings = reactive(new Map<number, 'UP' | 'DOWN'>())
const rating = computed<'UP' | 'DOWN' | null>(() =>
  props.message.id != null ? (ratings.get(props.message.id) ?? null) : null
)

async function rate(value: 'UP' | 'DOWN') {
  const id = props.message.id
  if (id == null || rating.value) return
  let reason = ''
  if (value === 'DOWN') {
    try {
      const { value: text } = await ElMessageBox.prompt(
        '请简要说明回答存在的问题，帮助我们改进',
        '反馈',
        {
          inputType: 'textarea',
          inputPlaceholder: '选填',
          confirmButtonText: '提交',
          cancelButtonText: '取消'
        }
      )
      reason = text ?? ''
    } catch {
      return // 用户取消
    }
  }
  try {
    // 评价接口后端阶段 4 已实现；失败时拦截器已统一弹错
    await submitFeedback(id, { rating: value, reason: reason || undefined })
    ratings.set(id, value)
    ElMessage.success('感谢你的反馈')
  } catch {
    // 错误提示已由拦截器统一处理
  }
}
</script>

<template>
  <div class="chat-message" :class="message.role === 'USER' ? 'is-user' : 'is-assistant'">
    <div class="msg-avatar">{{ message.role === 'USER' ? '我' : 'AI' }}</div>
    <div class="msg-body">
      <div class="msg-bubble">
        <!-- 生成中占位 -->
        <template v-if="message.loading">
          <span class="msg-loading">
            <el-icon class="is-loading"><Loading /></el-icon>
            正在检索并生成回答…
          </span>
        </template>

        <!-- 发送失败占位 -->
        <template v-else-if="message.error">
          <el-icon class="msg-error-icon"><CircleClose /></el-icon>
          <span>{{ message.content }}</span>
        </template>

        <template v-else>
          <!-- 用户消息按纯文本展示，AI 回答按 Markdown 渲染 -->
          <div
            v-if="message.role === 'ASSISTANT'"
            class="msg-text md-render"
            v-html="renderMarkdown(message.content)"
          ></div>
          <div v-else class="msg-text">{{ message.content }}</div>
          <div v-if="message.role === 'ASSISTANT' && message.answered === false" class="msg-refused">
            未在知识库中找到足够可靠的信息（已拒答）
          </div>
          <div v-else-if="message.sources && message.sources.length" class="msg-sources">
            <div class="msg-sources-label">来源（{{ message.sources.length }}）</div>
            <div class="msg-sources-list">
              <SourceCard v-for="(s, i) in message.sources" :key="`${message.id}-${i}`" :source="s" />
            </div>
          </div>
        </template>
      </div>

      <!-- P1：回答评价 -->
      <div
        v-if="message.role === 'ASSISTANT' && !message.loading && message.id != null"
        class="msg-actions"
      >
        <el-tooltip content="回答有帮助" placement="top">
          <el-button
            link
            :icon="ThumbUp"
            :type="rating === 'UP' ? 'success' : ''"
            :disabled="!!rating"
            @click="rate('UP')"
          />
        </el-tooltip>
        <el-tooltip content="回答有误" placement="top">
          <el-button
            link
            :icon="ThumbDown"
            :type="rating === 'DOWN' ? 'danger' : ''"
            :disabled="!!rating"
            @click="rate('DOWN')"
          />
        </el-tooltip>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-message {
  display: flex;
  gap: 10px;
  margin-bottom: 18px;
}
.chat-message.is-user {
  flex-direction: row-reverse;
}
.msg-avatar {
  flex: none;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  color: #fff;
}
.is-user .msg-avatar {
  background: var(--el-color-primary);
}
.is-assistant .msg-avatar {
  background: var(--el-color-success);
}
.msg-body {
  max-width: 72%;
  display: flex;
  flex-direction: column;
}
.is-user .msg-body {
  align-items: flex-end;
}
.msg-bubble {
  padding: 10px 14px;
  border-radius: 10px;
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
}
.is-user .msg-bubble {
  background: var(--el-color-primary-light-8);
  border-color: var(--el-color-primary-light-7);
}
.msg-text {
  white-space: pre-wrap;
}
/* markdown 渲染排版见全局 styles/markdown.css (.md-render) */
.msg-text.md-render {
  white-space: normal;
}
.msg-loading {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--el-text-color-secondary);
}
.msg-error-icon {
  vertical-align: -2px;
  color: var(--el-color-danger);
  margin-right: 4px;
}
.msg-refused {
  margin-top: 8px;
  display: inline-block;
  font-size: 12px;
  color: var(--el-color-warning);
  background: var(--el-color-warning-light-9);
  border-radius: 4px;
  padding: 2px 8px;
}
.msg-sources {
  margin-top: 10px;
}
.msg-sources-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}
.msg-sources-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.msg-actions {
  display: flex;
  gap: 2px;
  margin-top: 2px;
}
</style>
