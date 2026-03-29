package com.aiagent.service.impl;

import com.aiagent.common.dto.AgentRequest;
import com.aiagent.common.dto.AgentResponse;
import com.aiagent.common.Constants;
import com.aiagent.common.exception.AgentException;
import com.aiagent.service.AgentService;
import com.aiagent.service.LLMService;
import com.aiagent.service.MemoryService;
import com.aiagent.service.PromptBuilder;
import com.aiagent.service.ReActEngine;
import com.aiagent.service.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Agent 服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentServiceImpl implements AgentService {

    private final PromptBuilder promptBuilder;
    private final LLMService llmService;
    private final ToolRegistry toolRegistry;
    private final MemoryService memoryService;
    private final ReActEngine reActEngine;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String TASK_CACHE_PREFIX = "aiagent:task:";
    private static final long TASK_TTL_SECONDS = 3600;

    @Override
    public Mono<AgentResponse> invoke(AgentRequest request) {
        long startTime = System.currentTimeMillis();

        String sessionId = request.getSessionId() != null ? request.getSessionId() : UUID.randomUUID().toString();
        request.setSessionId(sessionId);

        log.info("Starting agent invoke - sessionId: {}, userInput: {}", sessionId, request.getUserInput());

        return Mono.fromCallable(() -> {
                    // 1. 构建 Prompt
                    String prompt = promptBuilder.build(request);

                    // 2. 获取记忆上下文
                    String memoryContext = memoryService.getShortTermMemory(sessionId);

                    // 3. 如果启用 RAG，获取长期记忆上下文
                    String ragContext = "";
                    if (Boolean.TRUE.equals(request.getEnableRag()) && request.getUserId() != null) {
                        ragContext = memoryService.getLongTermMemory(request.getUserId(), request.getUserInput());
                        log.debug("RAG context retrieved for user: {}, length: {}", request.getUserId(), ragContext.length());
                    }

                    // 4. 构建完整 Prompt (包含记忆 + RAG)
                    String fullPrompt;
                    if (!ragContext.isEmpty()) {
                        fullPrompt = promptBuilder.buildWithMemoryAndRag(prompt, memoryContext, ragContext);
                    } else {
                        fullPrompt = promptBuilder.buildWithMemory(prompt, memoryContext);
                    }

                    // 5. 执行 ReAct 推理
                    AgentResponse response = reActEngine.execute(fullPrompt, request);

                    // 6. 更新短期记忆
                    memoryService.updateShortTermMemory(sessionId, request.getUserInput(), response.getContent());

                    // 7. 计算耗时
                    response.setLatencyMs(System.currentTimeMillis() - startTime);
                    response.setSessionId(sessionId);
                    response.setResponseId(UUID.randomUUID().toString());

                    return response;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> log.error("Agent invoke error - sessionId: {}", sessionId, e));
    }

    @Override
    public Mono<String> invokeAsync(AgentRequest request) {
        String taskId = UUID.randomUUID().toString();

        return invoke(request)
                .doOnNext(response -> {
                    try {
                        String json = objectMapper.writeValueAsString(response);
                        redisTemplate.opsForValue().set(TASK_CACHE_PREFIX + taskId, json, TASK_TTL_SECONDS, TimeUnit.SECONDS);
                        log.info("Async task persisted to Redis: taskId={}", taskId);
                    } catch (Exception e) {
                        log.error("Failed to persist async task to Redis: taskId={}", taskId, e);
                    }
                })
                .thenReturn(taskId);
    }

    @Override
    public Mono<AgentResponse> getTaskStatus(String taskId) {
        try {
            String json = redisTemplate.opsForValue().get(TASK_CACHE_PREFIX + taskId);
            if (json == null || json.isEmpty()) {
                return Mono.error(new AgentException("Task not found or expired: " + taskId));
            }
            AgentResponse response = objectMapper.readValue(json, AgentResponse.class);
            return Mono.just(response);
        } catch (Exception e) {
            log.error("Failed to read async task from Redis: taskId={}", taskId, e);
            return Mono.error(new AgentException("Failed to retrieve task: " + taskId));
        }
    }

    @Override
    public Mono<String> invokeStream(AgentRequest request) {
        // 流式实现，后续完善
        return invoke(request)
                .map(AgentResponse::getContent);
    }
}
