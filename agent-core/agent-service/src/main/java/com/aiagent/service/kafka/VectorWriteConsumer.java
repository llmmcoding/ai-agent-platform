package com.aiagent.service.kafka;

import com.aiagent.common.Constants;
import com.aiagent.service.vector.VectorStoreFactory;
import com.aiagent.service.vector.VectorStoreFactory.VectorSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 向量批量写入 Consumer
 * 消费 Kafka 消息，批量写入向量数据库（支持 Milvus/pgvector）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorWriteConsumer {

    private final VectorStoreFactory vectorStoreFactory;
    private final ObjectMapper objectMapper;

    // 批量写入配置
    private static final int BATCH_SIZE = 100;
    private static final long FLUSH_INTERVAL_MS = 1000;  // 1秒强制刷新

    // 批量缓冲区
    private final ConcurrentHashMap<String, List<VectorWriteTask>> buffer = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @PostConstruct
    public void init() {
        // 启动定时刷新任务
        scheduler.scheduleAtFixedRate(this::flushAll, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("VectorWriteConsumer initialized with batch size: {}, flush interval: {}ms, provider: {}",
                BATCH_SIZE, FLUSH_INTERVAL_MS, vectorStoreFactory.getCurrentProvider());
    }

    /**
     * 消费向量写入消息
     */
    @KafkaListener(
            topics = Constants.KafkaTopic.VECTOR_WRITE,
            groupId = "${spring.kafka.vector-write.group-id:ai-agent-vector-write-consumer}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeVectorWrite(String message) {
        try {
            VectorWriteMessage writeMessage = objectMapper.readValue(message, VectorWriteMessage.class);
            addToBuffer(writeMessage);
        } catch (Exception e) {
            log.error("Failed to process vector write message: {}", e.getMessage(), e);
        }
    }

    /**
     * 添加到缓冲区
     */
    private void addToBuffer(VectorWriteMessage message) {
        String collection = message.getCollection();
        buffer.computeIfAbsent(collection, k -> new ArrayList<>())
                .add(new VectorWriteTask(message.getDocuments(), message.getEmbeddings()));

        // 检查是否达到批量大小
        if (buffer.get(collection).size() >= BATCH_SIZE) {
            flushCollection(collection);
        }
    }

    /**
     * 刷新指定 Collection 的缓冲区
     */
    private void flushCollection(String collection) {
        List<VectorWriteTask> tasks = buffer.remove(collection);
        if (tasks == null || tasks.isEmpty()) {
            return;
        }

        try {
            // 合并所有任务
            List<Map<String, Object>> allDocuments = new ArrayList<>();
            List<List<Float>> allEmbeddings = new ArrayList<>();

            for (VectorWriteTask task : tasks) {
                allDocuments.addAll(task.getDocuments());
                allEmbeddings.addAll(task.getEmbeddings());
            }

            // 批量写入
            VectorSearchService searchService = vectorStoreFactory.getSearchService();
            int count = searchService.insertVectors(collection, allDocuments, allEmbeddings);
            log.info("Batch inserted {} vectors into collection {} via {}", count, collection, vectorStoreFactory.getCurrentProvider());

        } catch (Exception e) {
            log.error("Failed to batch insert into collection {}: {}", collection, e.getMessage(), e);
            // 失败时重试逻辑可以在这里添加
        }
    }

    /**
     * 刷新所有缓冲区
     */
    private void flushAll() {
        Set<String> collections = new HashSet<>(buffer.keySet());
        for (String collection : collections) {
            flushCollection(collection);
        }
    }

    /**
     * 向量写入消息格式
     */
    @lombok.Data
    public static class VectorWriteMessage {
        private String collection;
        private List<Map<String, Object>> documents;
        private List<List<Float>> embeddings;
        private String action;  // upsert / delete
    }

    /**
     * 向量写入任务
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    private static class VectorWriteTask {
        private List<Map<String, Object>> documents;
        private List<List<Float>> embeddings;
    }
}
