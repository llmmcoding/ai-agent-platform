package com.aiagent.service.impl;

import com.aiagent.service.impl.PromptBuilderImpl;
import com.aiagent.common.dto.AgentRequest;
import com.aiagent.common.dto.AgentResponse;
import com.aiagent.common.Constants;
import com.aiagent.common.exception.AgentException;
import com.aiagent.service.LLMService;
import com.aiagent.service.ReActEngine;
import com.aiagent.service.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * ReAct 推理引擎实现
 * Thought → Action → Action Input → Observation → ... → Final
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReActEngineImpl implements ReActEngine {

    private final LLMService llmService;
    private final ToolRegistry toolRegistry;
    private final PromptBuilderImpl promptBuilder;
    private final ObjectMapper objectMapper;

    @Value("${aiagent.tool.timeout:30000}")
    private long toolTimeoutMs;

    @Override
    public AgentResponse execute(String prompt, AgentRequest request) {
        int maxIterations = request.getMaxIterations() != null ?
                request.getMaxIterations() : Constants.ReAct.MAX_ITERATIONS;

        List<AgentResponse.ToolCallRecord> toolCalls = new ArrayList<>();
        String fullPrompt = prompt;
        String lastObservation = null;
        String finalAnswer = null;

        for (int i = 0; i < maxIterations; i++) {
            log.debug("ReAct iteration {}/{}", i + 1, maxIterations);

            // 1. 调用 LLM 获取下一步行动
            AgentResponse llmResponse = llmService.call(fullPrompt, request);
            String content = llmResponse.getContent();

            // 2. 解析 LLM 输出
            PromptBuilderImpl.ReActStep step = promptBuilder.parseReActOutput(content);

            // 3. 如果有最终答案，结束循环
            if (step.getFinalAnswer() != null && !step.getFinalAnswer().isEmpty()) {
                finalAnswer = step.getFinalAnswer();
                log.info("ReAct completed with final answer after {} iterations", i + 1);
                break;
            }

            // 4. 如果没有行动，结束循环
            if (step.getAction() == null) {
                finalAnswer = content;
                log.info("ReAct ended with no action, using LLM response as final answer");
                break;
            }

            // 5. 执行 Tool (带超时控制)
            String toolResult;
            long toolStartTime = System.currentTimeMillis();
            try {
                Map<String, Object> actionInput = parseActionInput(step.getActionInput());
                CompletableFuture<String> toolFuture = toolRegistry.execute(
                        step.getAction(), actionInput, request
                );
                // 带超时的异步等待
                toolResult = toolFuture.get(toolTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                log.warn("Tool execution timed out: {}, timeout: {}ms", step.getAction(), toolTimeoutMs);
                toolResult = "Error: Tool execution timed out after " + toolTimeoutMs + "ms";
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                log.error("Tool execution failed: {}", step.getAction(), cause);
                toolResult = "Error: " + (cause != null ? cause.getMessage() : e.getMessage());
            } catch (Exception e) {
                log.error("Tool execution failed: {}", step.getAction(), e);
                toolResult = "Error: " + e.getMessage();
            }
            long toolDuration = System.currentTimeMillis() - toolStartTime;

            // 记录工具调用
            toolCalls.add(AgentResponse.ToolCallRecord.builder()
                    .toolName(step.getAction())
                    .input(step.getActionInput())
                    .output(toolResult)
                    .durationMs(toolDuration)
                    .status(toolResult.startsWith("Error:") ? "FAILED" : "SUCCESS")
                    .build());

            // 6. 构建下一轮 Prompt (添加之前的 Observation)
            fullPrompt = fullPrompt + "\n\n" + content +
                    "\nObservation: " + toolResult;
            lastObservation = toolResult;
        }

        // 如果达到最大迭代次数还没有最终答案
        if (finalAnswer == null) {
            log.warn("ReAct reached max iterations ({}) without final answer", maxIterations);
            finalAnswer = "抱歉，我需要更多时间来完成这个任务。当前已达到最大迭代次数限制。";
        }

        return AgentResponse.builder()
                .content(finalAnswer)
                .completed(true)
                .status("COMPLETED")
                .toolCalls(toolCalls)
                .build();
    }

    @Override
    public String step(String thought, String action, String actionInput, AgentRequest request) {
        if (action == null || action.isEmpty()) {
            throw new AgentException("Action cannot be empty");
        }

        try {
            Map<String, Object> input = parseActionInput(actionInput);
            CompletableFuture<String> resultFuture = toolRegistry.execute(
                    action, input, request
            );
            return resultFuture.get();
        } catch (Exception e) {
            log.error("Step execution failed for action: {}", action, e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 解析 Action Input JSON
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseActionInput(String actionInput) {
        if (actionInput == null || actionInput.isEmpty() || "null".equalsIgnoreCase(actionInput)) {
            return Map.of();
        }

        try {
            return objectMapper.readValue(actionInput, Map.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse action input as JSON: {}", actionInput);
            return Map.of("raw_input", actionInput);
        }
    }
}
