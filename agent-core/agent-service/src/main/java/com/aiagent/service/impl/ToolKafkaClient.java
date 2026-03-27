package com.aiagent.service.impl;

import com.aiagent.common.dto.AgentRequest;
import com.aiagent.common.Constants;
import com.aiagent.service.kafka.KafkaProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Tool Kafka 客户端
 * 用于通过 Kafka 调用 Python Worker 的 Tool 服务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolKafkaClient {

    private final KafkaProducer kafkaProducer;
    private final ObjectMapper objectMapper;

    /**
     * 异步执行工具
     */
    public CompletableFuture<String> executeToolAsync(String toolName, Map<String, Object> input, AgentRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeTool(toolName, input, request);
            } catch (Exception e) {
                log.error("Tool execution failed: {}", toolName, e);
                return "Error: " + e.getMessage();
            }
        });
    }

    /**
     * 同步执行工具 (通过 Kafka 发送请求并等待结果)
     * 注意: 这是异步发送，实际结果通过 Kafka Consumer 回调
     */
    public String executeTool(String toolName, Map<String, Object> input, AgentRequest request) {
        try {
            String inputJson = objectMapper.writeValueAsString(input);
            String traceId = request.getTraceId() != null ? request.getTraceId() : "";

            // 发送消息到 Kafka
            kafkaProducer.sendToolExecuteTask(toolName, inputJson, traceId);

            log.info("Tool task sent to Kafka: tool={}, traceId={}", toolName, traceId);

            // 注意: 由于 Kafka 是异步的，实际结果需要通过 Kafka Consumer 回调
            // 这里返回的是任务已提交的状态
            return "{\"status\":\"submitted\",\"tool\":\"" + toolName + "\",\"traceId\":\"" + traceId + "\"}";

        } catch (Exception e) {
            log.error("Failed to send tool task to Kafka: {}", toolName, e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 发送工具执行请求
     */
    public void sendToolRequest(String toolName, Map<String, Object> input, String traceId) {
        try {
            String inputJson = objectMapper.writeValueAsString(input);
            kafkaProducer.sendToolExecuteTask(toolName, inputJson, traceId);
            log.debug("Tool request sent: {}", toolName);
        } catch (Exception e) {
            log.error("Failed to send tool request: {}", toolName, e);
        }
    }
}
