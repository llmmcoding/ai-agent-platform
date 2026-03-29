package com.aiagent.service.vector;

import com.aiagent.service.milvus.MilvusService;
import com.aiagent.service.pgvector.PGVectorService;
import com.aiagent.service.search.KeywordSearchService;
import com.aiagent.service.monitor.VectorSearchHealthMonitor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.*;

/**
 * 向量搜索服务降级实现
 * 多级降级策略:
 * 1. 正常 → 2. 主向量库超时(200ms) → 3. 备用向量库 → 4. 关键词召回 → 5. 返回"服务降级"
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FallbackVectorSearchService implements VectorStoreFactory.VectorSearchService {

    private final MilvusService milvusService;
    private final PGVectorService pgVectorService;
    private final KeywordSearchService keywordSearchService;
    private final VectorSearchHealthMonitor healthMonitor;

    @Value("${aiagent.vector-store.provider:milvus}")
    private String primaryProvider;

    @Value("${aiagent.vector-store.fallback.timeout-ms:200}")
    private int fallbackTimeoutMs;

    @Value("${aiagent.vector-store.fallback.enabled:true}")
    private boolean fallbackEnabled;

    private VectorStoreFactory.VectorSearchService primaryService;
    private VectorStoreFactory.VectorSearchService secondaryService;
    private ExecutorService executor;

    @PostConstruct
    public void init() {
        // 使用固定大小线程池，避免高并发下无限创建线程导致 OOM
        executor = Executors.newFixedThreadPool(20);

        // 根据配置选择主/备服务
        if ("pgvector".equalsIgnoreCase(primaryProvider)) {
            primaryService = new PGVectorAdapter(pgVectorService);
            secondaryService = new MilvusAdapter(milvusService);
        } else {
            primaryService = new MilvusAdapter(milvusService);
            secondaryService = new PGVectorAdapter(pgVectorService);
        }

        log.info("FallbackVectorSearchService initialized: primary={}, fallbackTimeout={}ms, enabled={}",
                primaryProvider, fallbackTimeoutMs, fallbackEnabled);
    }

    @Override
    public List<Map<String, Object>> searchVectors(List<Float> queryEmbedding, String collection, int topK) {
        if (!fallbackEnabled) {
            // 降级未启用，直接返回主服务结果
            return primaryService.searchVectors(queryEmbedding, collection, topK);
        }

        long startTime = System.currentTimeMillis();

        // Level 1: 尝试主向量库 (带超时)
        try {
            List<Map<String, Object>> results = executeWithTimeout(
                    () -> primaryService.searchVectors(queryEmbedding, collection, topK),
                    fallbackTimeoutMs,
                    TimeUnit.MILLISECONDS
            );

            if (results != null && !results.isEmpty()) {
                healthMonitor.recordSuccess(primaryProvider, System.currentTimeMillis() - startTime);
                return results;
            }
        } catch (TimeoutException e) {
            log.warn("Primary vector search timeout ({}ms), trying fallback", fallbackTimeoutMs);
            healthMonitor.recordTimeout(primaryProvider);
            healthMonitor.recordDegradeTriggered("primary_timeout");
        } catch (Exception e) {
            log.error("Primary vector search failed: {}", e.getMessage());
            healthMonitor.recordFailure(primaryProvider, e);
            healthMonitor.recordDegradeTriggered("primary_error");
        }

        // Level 2: 尝试备用向量库
        try {
            String secondaryProvider = "pgvector".equals(primaryProvider) ? "milvus" : "pgvector";
            List<Map<String, Object>> results = executeWithTimeout(
                    () -> secondaryService.searchVectors(queryEmbedding, collection, topK),
                    fallbackTimeoutMs,
                    TimeUnit.MILLISECONDS
            );

            if (results != null && !results.isEmpty()) {
                healthMonitor.recordSuccess(secondaryProvider, System.currentTimeMillis() - startTime);
                log.info("Fallback to secondary provider succeeded");
                return results;
            }
        } catch (TimeoutException e) {
            log.warn("Secondary vector search timeout, trying keyword fallback");
            healthMonitor.recordDegradeTriggered("secondary_timeout");
        } catch (Exception e) {
            log.error("Secondary vector search failed: {}", e.getMessage());
            healthMonitor.recordDegradeTriggered("secondary_error");
        }

        // Level 3: 关键词召回降级
        try {
            log.info("Attempting keyword search fallback for collection: {}", collection);
            List<Map<String, Object>> results = keywordSearchService.search(
                    collection,
                    queryEmbedding.toString(), // 使用 embedding 作为查询提示
                    topK
            );

            if (results != null && !results.isEmpty()) {
                healthMonitor.recordDegradeTriggered("keyword_fallback");
                return results;
            }
        } catch (Exception e) {
            log.error("Keyword search fallback failed: {}", e.getMessage());
        }

        // Level 4: 完全降级
        healthMonitor.recordDegradeTriggered("total_failure");
        log.error("All vector search methods failed, returning empty results with degraded status");

        Map<String, Object> degradedResult = new HashMap<>();
        degradedResult.put("id", "DEGRADED_MODE");
        degradedResult.put("content", "[RAG 服务暂时降级，请稍后重试]");
        degradedResult.put("score", 0.0);
        degradedResult.put("degraded", true);

        return Collections.singletonList(degradedResult);
    }

    @Override
    public int insertVectors(String collection, List<Map<String, Object>> documents, List<List<Float>> embeddings) {
        // 写入主向量库，同时写入关键词索引
        int result = primaryService.insertVectors(collection, documents, embeddings);

        // 异步更新关键词索引
        try {
            for (Map<String, Object> doc : documents) {
                keywordSearchService.indexDocument(collection, (String) doc.get("id"),
                        (String) doc.get("content"), doc);
            }
        } catch (Exception e) {
            log.warn("Failed to update keyword index: {}", e.getMessage());
            // 不影响主流程
        }

        return result;
    }

    @Override
    public boolean deleteVectors(String collection, List<String> ids) {
        boolean result = primaryService.deleteVectors(collection, ids);

        // 异步删除关键词索引
        try {
            for (String id : ids) {
                keywordSearchService.deleteDocument(collection, id);
            }
        } catch (Exception e) {
            log.warn("Failed to delete from keyword index: {}", e.getMessage());
        }

        return result;
    }

    @Override
    public void createCollection(String collection) {
        primaryService.createCollection(collection);
        keywordSearchService.createIndex(collection);
    }

    @Override
    public boolean collectionExists(String collection) {
        return primaryService.collectionExists(collection);
    }

    /**
     * 带超时的执行
     */
    private <T> T executeWithTimeout(Callable<T> task, long timeout, TimeUnit unit) throws TimeoutException {
        Future<T> future = executor.submit(task);
        try {
            return future.get(timeout, unit);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Milvus 适配器
     */
    private static class MilvusAdapter implements VectorStoreFactory.VectorSearchService {
        private final MilvusService milvusService;

        public MilvusAdapter(MilvusService milvusService) {
            this.milvusService = milvusService;
        }

        @Override
        public List<Map<String, Object>> searchVectors(List<Float> queryEmbedding, String collection, int topK) {
            return milvusService.searchVectors(queryEmbedding, collection, topK);
        }

        @Override
        public int insertVectors(String collection, List<Map<String, Object>> documents, List<List<Float>> embeddings) {
            return milvusService.insertVectors(collection, documents, embeddings);
        }

        @Override
        public boolean deleteVectors(String collection, List<String> ids) {
            return milvusService.deleteVectors(collection, ids);
        }

        @Override
        public void createCollection(String collection) {
            milvusService.createCollection(collection);
        }

        @Override
        public boolean collectionExists(String collection) {
            return milvusService.collectionExists(collection);
        }
    }

    /**
     * PGVector 适配器
     */
    private static class PGVectorAdapter implements VectorStoreFactory.VectorSearchService {
        private final PGVectorService pgVectorService;

        public PGVectorAdapter(PGVectorService pgVectorService) {
            this.pgVectorService = pgVectorService;
        }

        @Override
        public List<Map<String, Object>> searchVectors(List<Float> queryEmbedding, String collection, int topK) {
            return pgVectorService.searchVectors(queryEmbedding, collection, topK);
        }

        @Override
        public int insertVectors(String collection, List<Map<String, Object>> documents, List<List<Float>> embeddings) {
            return pgVectorService.insertVectors(collection, documents, embeddings);
        }

        @Override
        public boolean deleteVectors(String collection, List<String> ids) {
            return pgVectorService.deleteVectors(collection, ids);
        }

        @Override
        public void createCollection(String collection) {
            pgVectorService.createCollection(collection);
        }

        @Override
        public boolean collectionExists(String collection) {
            return pgVectorService.collectionExists(collection);
        }
    }
}
