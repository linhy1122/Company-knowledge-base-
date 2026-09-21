import { http, getToken } from '@/utils/request'

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
  archived?: boolean | null
  createdAt: string
}

/** 会话列表筛选范围 */
export type ConvScope = 'active' | 'all' | 'archived'

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

/** 我的会话列表（scope: active 进行中 / archived 已归档 / all 全部，默认 active） */
export function listConversations(scope: ConvScope = 'active') {
  return http.get<Conversation[]>('/conversations', { scope })
}

/** 新建会话（title 可空，首条消息后由后端自动填充标题） */
export function createConversation(title?: string) {
  return http.post<Conversation>('/conversations', title ? { title } : {})
}

/** 重命名会话（title 可空即为不修改；modelId/kbIds 预留） */
export function updateConversation(id: number, data: Partial<{ title: string }>) {
  return http.put<Conversation>(`/conversations/${id}`, data)
}

/** 归档 / 取消归档会话（列表隐藏，可恢复；不代表删除） */
export function setConversationArchived(id: number, archived: boolean) {
  return http.put<void>(`/conversations/${id}/archive`, { archived })
}

/** 跨会话续接：以 id 会话为源复制出新会话（含历史），返回新会话 */
export function forkConversation(id: number, title?: string) {
  return http.post<Conversation>(`/conversations/${id}/fork`, title ? { title } : {})
}

/** 删除会话（级联删除其全部消息，硬删） */
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

/* ---------------- 流式问答（功能扩展04，SSE 打字机） ---------------- */

/** SSE 事件回调（事件顺序：meta → sources → delta… → done；异常发 error）。 */
export interface StreamHandlers {
  onMeta?: (m: { conversationId: number }) => void
  onSources?: (d: { sources: Source[]; answered: boolean; similarity?: number }) => void
  onDelta: (text: string) => void
  onDone: (d: { messageId: number; content: string; answered: boolean }) => void
  onError?: (msg: string) => void
}

/**
 * 流式提问：POST /api/messages/stream，用 fetch + ReadableStream 逐事件解析（含增量 text 打字机）。
 * 非流式接口不可用时（404/断连）会抛错，调用方回退到 sendMessage。
 */
export async function streamMessage(
  conversationId: number,
  content: string,
  h: StreamHandlers,
  signal?: AbortSignal
) {
  const base = import.meta.env.VITE_API_BASE_URL || '/api'
  const res = await fetch(`${base}/messages/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${getToken()}`
    },
    body: JSON.stringify({ conversationId, content }),
    signal
  })
  if (!res.ok || !res.body) {
    throw new Error(`流式请求失败：HTTP ${res.status}`)
  }
  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buf = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    let idx: number
    // 按空行切分 SSE 事件块（每个事件 data: <json>\n\n）
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const block = buf.slice(0, idx)
      buf = buf.slice(idx + 2)
      for (const line of block.split('\n')) {
        if (!line.startsWith('data:')) continue
        const data = line.slice(5).trim()
        if (!data) continue
        let ev: { type: string; [k: string]: unknown }
        try {
          ev = JSON.parse(data)
        } catch {
          continue
        }
        switch (ev.type) {
          case 'meta':
            h.onMeta?.(ev as unknown as { conversationId: number })
            break
          case 'sources':
            h.onSources?.(ev as unknown as { sources: Source[]; answered: boolean; similarity?: number })
            break
          case 'delta':
            h.onDelta((ev.content as string) ?? '')
            break
          case 'done':
            h.onDone(ev as unknown as { messageId: number; content: string; answered: boolean })
            break
          case 'error':
            h.onError?.((ev.message as string) || '生成失败')
            break
        }
      }
    }
  }
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
