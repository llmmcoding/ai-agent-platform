package com.aiagent.service.kafka;

import com.aiagent.common.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Kafka 生产者
 * 用于向 Python Worker 发送异步任务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;

    /**
     * 发送工具执行任务到 Python Worker
     */
    public CompletableFuture<SendResult<String, String>> sendToolExecuteTask(String toolName, String inputJson, String traceId) {
        String message = buildMessage(toolName, inputJson, traceId);

        log.debug("Sending tool execute task to Kafka: topic={}, tool={}", Constants.KafkaTopic.TOOL_EXECUTE, toolName);

        return kafkaTemplate.send(Constants.KafkaTopic.TOOL_EXECUTE, toolName, message)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to send tool task: {}", toolName, ex);
                    } else {
                        log.debug("Tool task sent successfully: {}, partition={}, offset={}",
                                toolName,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }

    /**
     * 发送 RAG 查询任务
     */
    public CompletableFuture<SendResult<String, String>> sendRagQueryTask(String query, String collection, String userId) {
        String message = buildRagMessage(query, collection, userId);

        log.debug("Sending RAG query task to Kafka: topic={}, collection={}", Constants.KafkaTopic.RAG_QUERY, collection);

        return kafkaTemplate.send(Constants.KafkaTopic.RAG_QUERY, collection, message)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to send RAG query task", ex);
                    } else {
                        log.debug("RAG query sent successfully: partition={}, offset={}",
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }

    /**
     * 发送记忆摘要任务
     */
    public CompletableFuture<SendResult<String, String>> sendMemorySummaryTask(String sessionId, String userId, String memoryContent) {
        String message = buildMemorySummaryMessage(sessionId, userId, memoryContent);

        log.debug("Sending memory summary task to Kafka: sessionId={}", sessionId);

        return kafkaTemplate.send(Constants.KafkaTopic.MEMORY_SUMMARY, sessionId, message)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to send memory summary task", ex);
                    }
                });
    }

    private String buildMessage(String toolName, String inputJson, String traceId) {
        return String.format("""
                {
                    "tool_name": "%s",
                    "input": %s,
                    "trace_id": "%s",
                    "timestamp": %d
                }
                """, toolName, inputJson, traceId != null ? traceId : "", System.currentTimeMillis());
    }

    private String buildRagMessage(String query, String collection, String userId) {
        return String.format("""
                {
                    "query": "%s",
                    "collection": "%s",
                    "user_id": "%s",
                    "timestamp": %d
                }
                """, query, collection, userId != null ? userId : "", System.currentTimeMillis());
    }

    private String buildMemorySummaryMessage(String sessionId, String userId, String memoryContent) {
        return String.format("""
                {
                    "session_id": "%s",
                    "user_id": "%s",
                    "memory": %s,
                    "timestamp": %d
                }
                """, sessionId, userId, memoryContent, System.currentTimeMillis());
    }
}
