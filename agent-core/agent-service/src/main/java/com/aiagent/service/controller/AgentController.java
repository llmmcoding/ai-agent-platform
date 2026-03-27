package com.aiagent.service.controller;

import com.aiagent.common.dto.AgentRequest;
import com.aiagent.common.dto.AgentResponse;
import com.aiagent.common.Result;
import com.aiagent.service.AgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * Agent API 控制器
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/agent")
@Tag(name = "Agent", description = "AI Agent 核心能力接口")
public class AgentController {

    private final AgentService agentService;

    /**
     * 同步执行 Agent
     */
    @PostMapping("/invoke")
    @Operation(summary = "同步执行 Agent", description = "同步方式执行 Agent，等待完整结果返回")
    public Mono<Result<AgentResponse>> invoke(@Valid @RequestBody AgentRequest request) {
        log.info("Agent invoke request - sessionId: {}, userId: {}, input: {}",
                request.getSessionId(), request.getUserId(), request.getUserInput());

        return agentService.invoke(request)
                .map(Result::success)
                .onErrorResume(e -> {
                    log.error("Agent invoke error", e);
                    return Mono.just(Result.error(e.getMessage()));
                });
    }

    /**
     * 异步执行 Agent
     */
    @PostMapping("/invoke/async")
    @Operation(summary = "异步执行 Agent", description = "异步方式执行 Agent，立即返回追踪 ID")
    public Mono<Result<String>> invokeAsync(@Valid @RequestBody AgentRequest request) {
        log.info("Agent async invoke request - sessionId: {}, userId: {}",
                request.getSessionId(), request.getUserId());

        return agentService.invokeAsync(request)
                .map(taskId -> Result.success("任务已提交", taskId))
                .onErrorResume(e -> {
                    log.error("Agent async invoke error", e);
                    return Mono.just(Result.error(e.getMessage()));
                });
    }

    /**
     * 查询异步任务状态
     */
    @GetMapping("/task/{taskId}")
    @Operation(summary = "查询任务状态", description = "查询异步任务的执行状态和结果")
    public Mono<Result<AgentResponse>> getTaskStatus(@PathVariable String taskId) {
        return agentService.getTaskStatus(taskId)
                .map(Result::success)
                .onErrorResume(e -> Mono.just(Result.error(e.getMessage())));
    }

    /**
     * 流式执行 Agent
     */
    @PostMapping("/invoke/stream")
    @Operation(summary = "流式执行 Agent", description = "流式方式执行 Agent，SSE 返回中间结果")
    public Mono<Result<String>> invokeStream(@Valid @RequestBody AgentRequest request) {
        log.info("Agent stream invoke request - sessionId: {}, userId: {}",
                request.getSessionId(), request.getUserId());

        return agentService.invokeStream(request)
                .map(Result::success)
                .onErrorResume(e -> {
                    log.error("Agent stream invoke error", e);
                    return Mono.just(Result.error(e.getMessage()));
                });
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    @Operation(summary = "健康检查")
    public Mono<Result<String>> health() {
        return Mono.just(Result.success("Agent service is healthy"));
    }
}
