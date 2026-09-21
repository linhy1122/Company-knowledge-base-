import { http } from '@/utils/request'

/** 答案来源片段（SourceVO：documentId / title / chunkId / similarity，功能扩展01 增高亮区间） */
export interface Source {
  documentId: number
  title: string
  chunkId: string
  similarity: number
  chunkStart?: number
  chunkEnd?: number
}

/** 会话（GET/POST /api/conversations 返回项） */
export interface Conversation {
  id: number
  title: string | null
  createdAt: string
}

/** 历史消息（GET /api/conversations/{id}/messages 返回项） */
export interface Message {
  id: number
  role: 'USER' | 'ASSISTANT'
  content: string
  sources?: Source[] | null
  answered?: boolean | null
  createdAt: string
}

/** 提问返回（POST /api/messages，便于前端即时渲染，无需二次查历史） */
export interface SendMessageResult {
  messageId: number
  content: string
  sources: Source[]
  answered: boolean
}

/* ---------------- 会话 ---------------- */

/** 我的会话列表（按创建时间倒序） */
export function listConversations() {
  return http.get<Conversation[]>('/conversations')
}

/** 新建会话（title 可空，首条消息后由后端自动填充标题） */
export function createConversation(title?: string) {
  return http.post<Conversation>('/conversations', title ? { title } : {})
}

/** 删除会话（级联删除其全部消息） */
export function deleteConversation(id: number) {
  return http.delete<void>(`/conversations/${id}`)
}

/* ---------------- 消息 ---------------- */

/** 会话历史消息（按时间升序） */
export function getConversationMessages(id: number) {
  return http.get<Message[]>(`/conversations/${id}/messages`)
}

/** 提问并回答：检索 → 生成 → 落库（user + assistant 两条）→ 返回答案 */
export function sendMessage(data: { conversationId: number; content: string }) {
  return http.post<SendMessageResult>('/messages', data, { timeout: 180000 })
}

/* ---------------- 评价（P1，后端阶段 4 提供） ---------------- */

/** 回答评价（rating: UP 赞 / DOWN 踩 + 可选 reason；接口未实现时可传 { silent: true } 静默降级） */
export function submitFeedback(
  messageId: number,
  data: { rating: 'UP' | 'DOWN'; reason?: string },
  config?: { silent?: boolean }
) {
  return http.post<void>(`/messages/${messageId}/feedback`, data, config)
}
