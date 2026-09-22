package com.corpedia.controller;

import com.corpedia.common.Result;
import com.corpedia.dto.request.SendMessageRequest;
import com.corpedia.dto.response.SendMessageResultVO;
import com.corpedia.security.UserContext;
import com.corpedia.security.UserContextHolder;
import com.corpedia.service.MessageService;
import com.corpedia.service.StreamingChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "消息问答", description = "提问 → 检索 → 生成 → 落库 → 返回（RAG 主链路）")
@RestController
@RequestMapping("/messages")
public class MessageController {

    /** SSE 连接超时：300s，超时后由 emitter 自行收尾（onTimeout 内 complete）。 */
    private static final long SSE_TIMEOUT_MS = 300_000L;

    private final MessageService messageService;
    private final StreamingChatService streamingChatService;

    public MessageController(MessageService messageService, StreamingChatService streamingChatService) {
        this.messageService = messageService;
        this.streamingChatService = streamingChatService;
    }

    @Operation(summary = "提问并回答")
    @PostMapping
    public Result<SendMessageResultVO> send(@Valid @RequestBody SendMessageRequest req) {
        return Result.ok(messageService.ask(UserContextHolder.get().userId(),
                req.conversationId(), req.content()));
    }

    @Operation(summary = "提问并流式回答（SSE，打字机）")
    @PostMapping("/stream")
    public SseEmitter stream(@Valid @RequestBody SendMessageRequest req) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitter.onTimeout(() -> emitter.complete());
        emitter.onError(e -> emitter.complete());
        // 请求线程捕获用户上下文，随异步任务带入（sseExecutor 线程无 SecurityContext/ThreadLocal）
        UserContext userCtx = UserContextHolder.get();
        // 异步推流，方法立即返回 emitter；后台线程逐事件写入
        streamingChatService.stream(userCtx, userCtx.userId(), req.conversationId(), req.content(), emitter);
        return emitter;
    }
}