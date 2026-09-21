# 功能扩展 04 · 流式输出（SSE）——答案打字机效果

> 配套《后端实施方案.md》与《前端实施计划.md》。目标：把 `POST /api/messages` 的「一次性返回」升级为 **SSE 流式返回**，前端逐 token 渲染（打字机效果），同时保留非流式接口作为回退。
>
> 涉及：`d:\Corpedia\backend\` + `d:\Corpedia\frontend\`。

---

## 一、现状与缺口（已核实）

- **后端链路**：`MessageController.send` → `MessageService.ask`（**事务内**：插 USER → `ragChat.chat()` 阻塞返回完整 `ChatResult` → 插 ASSISTANT → 返回整串）。
- **生成前已知信息**：`RagChatService.chat` 的顺序是 **检索（RetrievedChunk + similarity + answered）→ 拼 Prompt → ChatClient 生成**。即 **sources / answered / similarity 在第一个 token 之前就已确定**，只有 `content` 需要逐步产出。→ 这让「先发来源、再逐字推流」的实现非常干净。
- **前端**：`sendMessage` 用 axios POST，`ChatView.send` 塞入 loading 占位直到整体返回才替换（`L131-L146`）。
- **spring.mvc** 是 Spring MVC（非 WebFlux）。用 `SseEmitter` 即可，**无需新增 webflux 依赖**。

---

## 二、方案设计

### 2.1 接口

新增流式端点（保留非流式走回退/测试）：

```
POST /api/messages/stream      application/json { conversationId, content }
响应:  text/event-stream; charset=utf-8
```

### 2.2 SSE 事件协议（每行 `data: <json>\n\n`）

| type | 时机 | data |
|---|---|---|
| `meta` | 建流后、检索前 | `{"type":"meta","conversationId":5}` |
| `sources` | 检索完成后、首个 token 前 | `{"type":"sources","sources":[{documentId,title,chunkId,similarity}],"answered":true,"similarity":0.58}` |
| `delta` | 逐 token（可合并为小批） | `{"type":"delta","content":"根据员工手册…"}` |
| `done` | 完成 | `{"type":"done","messageId":12,"content":"完整文本"}` |
| `error` | 异常 | `{"type":"error","message":"…"}` |

> 前端可按 `content` 增量拼接实现打字机；`sources/answered/similarity` 提前送达即可先渲染来源样式。

### 2.3 后端改造

**抽取流式服务**（避免污染 `MessageService.ask` 事务）：

- `RagChatService` 新增：
  ```java
  public ChatPlan prepare(Long userId, Long conversationId, String question)
  // 返回：检索到的 sources / answered / similarity + 拼好的 ChatClient prompt（可不返回 content）
  ```
  把现有 `chat` 中「检索 → 阈值 → 拼 Prompt」部分抽成 `prepare`，`chat` 复用它做阻塞版；流式版复用同一 `prepare`，保证两路答案一致。
- 新增 `StreamingChatService`（`@Service`，注入 `RagChatService`/`MessageMapper`/`ConversationService`/`ObjectMapper`）：
  ```java
  public void stream(Long userId, Long conversationId, String question, SseEmitter emitter) { ... }
  ```
  流程：
  1. `ConversationService.requireConversation`（校验归属；归档会话自动恢复 active，同功能02）；
  2. 插 USER 消息（**独立事务，先提交**，不在长流中持有事务）；
  3. `prepare()` 得 `ChatPlan`（sources/answered/similarity）；
  4. `emitter.send(sources事件)`；
  5. `chatClient.prompt(...).stream().content()` → `flux.subscribe(delta -> { sb.append; 合并后 emitter.send(delta); }, err -> emitter.send(error)+complete, () -> 持久化ASSISTANT+send(done)+complete)`；
  6. **持久化 ASSISTANT**：在 `onComplete`（或 `onError` 时按已累计内容）落库——`content=sb`、sources/answered/similarity 来自 `prepare`、`response_ms` 记录总耗时（含推流）。
- `MessageController` 新增 `POST /api/messages/stream`：
  - 创建 `SseEmitter`（超时如 300_000ms、`COMPLETE_WITH_NO_DATA=false`）；
  - `emitter.onTimeout/onError` → 复位为 0、complete，并尽力用已累计内容落库；
  - 返回 `emitter`。

> 注意：`SseEmitter` 线程上执行长任务会占线程池；预览/轻量可用 `StreamingResponseBody`+异步，但本项目以 SseEmitter + 业务线程池（`AsyncConfig` 已有 `documentPipelineExecutor`）在 `stream()` 方法上 `@Async("sseExecutor")` 执行推流，避免占用 caller 线程。

- `SseEmitter` 事件为 JSON 字符串，序列化借用 `ObjectMapper`；SSE 响应头 `text/event-stream;charset=utf-8` 由 Spring 自动设置。

### 2.4 阈值/回答一致性

- 流式与非流式共用 `prepare()`，`similarity-threshold` 判定与 sources 完全一致，避免两种入口结论漂移。

---

## 三、前端改造

### 3.1 流式请求封装（绕过 axios）

axios 对流式不友好，改 `fetch` + `ReadableStream`。`src/api/conversation.ts` 新增：

```ts
export interface StreamHandlers {
  onMeta?: (m: { conversationId: number }) => void
  onSources?: (d: { sources: Source[]; answered: boolean; similarity?: number }) => void
  onDelta: (text: string) => void
  onDone: (d: { messageId: number; content: string }) => void
  onError?: (msg: string) => void
}
export async function streamMessage(convId: number, content: string, h: StreamHandlers, signal?: AbortSignal) {
  const res = await fetch(`${import.meta.env.VITE_API_BASE ?? '/api'}/messages/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${userStore.token}` },
    body: JSON.stringify({ conversationId: convId, content }),
    signal,
  })
  const reader = res.body!.getReader()
  const dec = new TextDecoder('utf-8')
  let buf = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buf += dec.decode(value, { stream: true })
    let idx
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const ev = buf.slice(0, idx); buf = buf.slice(idx + 2)
      for (const line of ev.split('\n')) {
        if (!line.startsWith('data:')) continue
        const d = JSON.parse(line.slice(5).trim()); switch (d.type) { /* 分发 */ }
      }
    }
  }
}
```
> 实测 `VITE_API_BASE` 后端前缀为 `/api`（Vite 代理已配），headers 可复用既有 token 读取；若已有 `fetch` 封装则复用。

### 3.2 `ChatView.vue` 打字机

- `send()` 增加流式分支（默认启用，可由设置/失败兜底关闭）：
  1. 建/取会话（同现有逻辑）；
  2. 推 USER + ASSISTANT 占位（`loading:true, content:''`）；
  3. `streamMessage(concat 增量到占位 content)` → `onDelta` 时 `replaceMessage(uid,{...content:acc})`，配合现有 `watch` 自动滚动实现打字机；
  4. `onSources` 时先设好 sources/answered；
  5. `onDone` 用返回完整 content 覆盖占位（含 messageId 供评价）；
  6. `onError` → 显示失败占位（同现有 catch 分支）。
- 用 `AbortSignal` 支持「停止生成」按钮（可选，推荐）。
- **非流式回退**：若接口 404/网络不支持，catch 后走既有 `sendMessage` 完整返回路径（`canStream = false` 标记）。
- 首轮自动标题刷新逻辑（`isFirstExchange → loadConversations`）沿用。

### 3.3 前端清单

- [x] `api/conversation.ts`：`streamMessage`（fetch/SSE 解析）
- [x] `ChatView.vue`：流式分支 + 打字机 + 停止/回退
- [x] `npm run build`（vue-tsc + vite）通过

---

## 四、验收

1. `curl -N -H "Authorization: Bearer <token>" -H "Content-Type: application/json" -d @body.json localhost:8080/api/messages/stream` → 逐步出现 `sources`、多个 `delta`、`done`，且顺序为 meta→sources→delta…→done。
2. 浏览器提问：答案逐字出现（打字机）、来源卡片在首字前渲染、评价可用。
3. 流式答案与服务端持久化一致（刷新历史/统计可见该 ASSISTANT 消息）。
4. 客户端中途断开 → 后端将已累计内容落库并正常结束（无悬挂连接）。
5. 非流式 `/api/messages` 仍可用（回退路径）。

---

## 五、风险与注意事项

| 风险 | 应对 |
|---|---|
| 长连接占用线程 | `@Async("sseExecutor")` 独立小线程池；设置 `SseEmitter` 超时与 `onTimeout` 清理 |
| 事务跨流式长请求 | 只在关键写点用短事务（USER 前置提交；ASSISTANT 完成落库），SNI 保证数据一致 |
| 流式与阻塞结论漂移 | 共用 `prepare()`，阈值/来源一致 |
| 中文分块/半字符 | SseEmitter 按 UTF-8 序列化；`delta` 合并为小批避免极端分半（前端 `TextDecoder` 已按流组装） |
| 前端兼容 | 保留非流式回退；`streamMessage` 用 fetch，token 单独注入 |
| SseEmitter 异步分派被 Security 拒绝 | SSE 触发 ASYNC 分派与 ERROR 分派，未再携带鉴权上下文而被 `AuthorizationFilter` 匿名拒绝（`AuthorizationDeniedException`）。已在 SecurityConfig 对 `DispatcherType.ASYNC/ERROR` `permitAll`；初请求仍由 JWT 鉴权，放行安全 |
| 异步线程丢失用户上下文 → 全员拒答 | `UserContextHolder` 是 ThreadLocal，`@Async` 线程上为空，`canUseSource` 判空返回 false → 所有来源被过滤 → 兜底拒答。已在请求线程捕获 `UserContext` 带入异步方法，用时 `set`/`finally clear`，权限判定在异步线程照常生效 |

> 后续可扩展：deep-resp 思考链分段、可中断生成、语音朗读联动。