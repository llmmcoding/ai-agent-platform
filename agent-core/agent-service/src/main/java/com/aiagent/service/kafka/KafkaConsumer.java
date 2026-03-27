package com.aiagent.service.kafka;

import com.aiagent.common.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka 消费者
 * 用于接收 Python Worker 的处理结果
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaConsumer {

    /**
     * 消费工具执行结果
     */
    @KafkaListener(
            topics = Constants.KafkaTopic.TOOL_RESULT,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeToolResult(String message) {
        try {
            log.debug("Received tool result: {}", message);
            // 解析消息并处理
            // 实际需要根据消息格式解析，更新任务状态等
        } catch (Exception e) {
            log.error("Failed to process tool result", e);
        }
    }

    /**
     * 消费 RAG 查询结果
     */
    @KafkaListener(
            topics = Constants.KafkaTopic.RAG_RESULT,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeRagResult(String message) {
        try {
            log.debug("Received RAG result: {}", message);
            // 解析消息并处理
        } catch (Exception e) {
            log.error("Failed to process RAG result", e);
        }
    }

    /**
     * 消费记忆摘要结果
     */
    @KafkaListener(
            topics = Constants.KafkaTopic.MEMORY_SUMMARY,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeMemorySummaryResult(String message) {
        try {
            log.debug("Received memory summary result: {}", message);
            // 解析消息并保存到长期记忆
        } catch (Exception e) {
            log.error("Failed to process memory summary result", e);
        }
    }
}
