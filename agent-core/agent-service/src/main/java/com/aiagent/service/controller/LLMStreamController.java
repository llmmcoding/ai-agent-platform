package com.aiagent.service.controller;

import com.aiagent.common.dto.AgentRequest;
import com.aiagent.service.streaming.SSEHelper;
import com.aiagent.service.streaming.StreamingLLMService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.logging.Level;

/**
 * LLM 流式 API 控制器
 * 提供真正的流式 LLM 调用接口
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/llm")
@Tag(name = "LLM Stream", description = "流式 LLM 调用接口")
public class LLMStreamController {

    private final StreamingLLMService streamingLLMService;
    private final SSEHelper sseHelper;

    /**
     * 流式生成 (返回 SSE 格式)
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式生成", description = "流式调用 LLM，返回 SSE 格式的 token 流")
    public Flux<StreamingLLMService.ServerSentEvent> streamGenerate(@RequestBody AgentRequest request) {
        log.info("LLM stream request - provider: {}, input length: {}",
                request.getLlmProvider(), request.getUserInput() != null ? request.getUserInput().length() : 0);

        return streamingLLMService.streamGenerateSSE(request.getUserInput(), request)
                .mergeWith(sseHelper.heartbeat().map(sse ->
                        StreamingLLMService.ServerSentEvent.of(sse.data())))
                .onErrorResume(e -> {
                    log.error("LLM stream error: {}", e.getMessage(), e);
                    return Flux.just(StreamingLLMService.ServerSentEvent.error(e.getMessage()));
                });
    }

    /**
     * 流式生成 (返回纯文本 token 流)
     */
    @PostMapping(value = "/stream/tokens", produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(summary = "流式生成 (纯文本)", description = "流式调用 LLM，返回纯文本 token 流")
    public Flux<String> streamGenerateTokens(@RequestBody AgentRequest request) {
        log.info("LLM stream tokens request - provider: {}", request.getLlmProvider());

        return streamingLLMService.streamGenerate(request.getUserInput(), request)
                .onErrorResume(e -> {
                    log.error("LLM stream error: {}", e.getMessage());
                    return Flux.error(e);
                });
    }

    /**
     * 流式生成 (NDJSON 格式)
     */
    @PostMapping(value = "/stream/ndjson", produces = "application/x-ndjson")
    @Operation(summary = "流式生成 (NDJSON)", description = "流式调用 LLM，返回 NDJSON 格式")
    public Flux<String> streamGenerateNDJSON(@RequestBody AgentRequest request) {
        log.info("LLM stream NDJSON request - provider: {}", request.getLlmProvider());

        return streamingLLMService.streamGenerate(request.getUserInput(), request)
                .map(token -> "{\"token\":\"" + token.replace("\"", "\\\"") + "\"}")
                .onErrorResume(e -> {
                    log.error("LLM stream error: {}", e.getMessage());
                    return Flux.error(e);
                });
    }
}
