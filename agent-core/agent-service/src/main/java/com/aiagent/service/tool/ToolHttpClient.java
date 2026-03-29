package com.aiagent.service.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Tool HTTP 客户端
 * 通过 HTTP REST 同步调用 Python Worker 的 Tool 服务
 * 替代 Kafka 异步方式，获得实际执行结果
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolHttpClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${aiagent.python-worker.url:http://localhost:8001}")
    private String pythonWorkerUrl;

    @Value("${aiagent.tool.http-timeout:30000}")
    private int httpTimeout;

    /**
     * 同步执行工具
     *
     * @param toolName 工具名称
     * @param input 输入参数
     * @return 工具执行结果 (JSON 字符串)
     */
    public String executeTool(String toolName, Map<String, Object> input) {
        try {
            log.debug("Executing tool via HTTP: {}", toolName);

            ToolRequest request = new ToolRequest(toolName, input);

            ToolResponse response = webClient.post()
                    .uri(pythonWorkerUrl + "/api/v1/tools/execute")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(ToolResponse.class)
                    .timeout(Duration.ofMillis(httpTimeout))
                    .block();

            if (response != null && response.isSuccess()) {
                log.debug("Tool execution success: {}", toolName);
                return objectMapper.writeValueAsString(response.getResult());
            }

            String errorMsg = response != null ? response.getError() : "Unknown error";
            log.warn("Tool execution failed: {}, error: {}", toolName, errorMsg);
            return "{\"error\":\"" + errorMsg + "\"}";

        } catch (Exception e) {
            log.error("Tool HTTP call failed: {}, error: {}", toolName, e.getMessage());
            return "{\"error\":\"HTTP call failed: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 异步执行工具
     */
    public CompletableFuture<String> executeToolAsync(String toolName, Map<String, Object> input) {
        return CompletableFuture.supplyAsync(() -> executeTool(toolName, input));
    }

    /**
     * 批量执行工具 (串行)
     */
    public String executeTools(String toolName, Map<String, Object> input, int maxRetries) {
        int attempt = 0;
        while (attempt < maxRetries) {
            try {
                return executeTool(toolName, input);
            } catch (Exception e) {
                attempt++;
                if (attempt >= maxRetries) {
                    log.error("Tool execution failed after {} retries: {}", maxRetries, toolName);
                    return "{\"error\":\"Max retries exceeded: " + e.getMessage() + "\"}";
                }
                log.warn("Tool execution retry {}: {}", attempt, toolName);
            }
        }
        return "{\"error\":\"Unknown error\"}";
    }

    /**
     * 健康检查
     */
    public boolean isHealthy() {
        try {
            webClient.get()
                    .uri(pythonWorkerUrl + "/health")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();
            return true;
        } catch (Exception e) {
            log.warn("Tool HTTP client health check failed: {}", e.getMessage());
            return false;
        }
    }

    // ==================== 请求/响应 DTO ====================

    @lombok.Data
    @lombok.AllArgsConstructor
    private static class ToolRequest {
        private String tool;
        private Map<String, Object> input;
    }

    @lombok.Data
    private static class ToolResponse {
        private boolean success;
        private Object result;
        private String error;
        private long executionTimeMs;
    }
}
