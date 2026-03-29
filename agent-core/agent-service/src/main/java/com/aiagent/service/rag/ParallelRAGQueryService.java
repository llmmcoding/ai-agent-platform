package com.aiagent.service.rag;

import com.aiagent.service.embedding.EmbeddingService;
import com.aiagent.service.embedding.EmbeddingServiceFactory;
import com.aiagent.service.memory.EnhancedMemoryService;
import com.aiagent.service.memory.MemoryEntry;
import com.aiagent.service.memory.MemoryType;
import com.aiagent.service.vector.VectorStoreFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 并行 RAG 查询服务
 * 优化点:
 * 1. 短期记忆和长期记忆并行获取
 * 2. 批量查询时并行 embedding
 * 3. 多 collection 搜索并行化
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParallelRAGQueryService {

    private final EnhancedMemoryService memoryService;
    private final EmbeddingServiceFactory embeddingFactory;
    private final VectorStoreFactory vectorStoreFactory;

    // 使用固定大小线程池避免高并发下 OOM（newCachedThreadPool 可能无限创建线程）
    private ExecutorService executor = Executors.newFixedThreadPool(20);

    /**
     * 并行获取记忆上下文
     * 短期记忆和长期记忆同时获取
     */
    public RAGContext parallelRetrieve(String userId, String query) {
        long startTime = System.currentTimeMillis();

        try {
            // 并行执行短期记忆和长期记忆查询
            CompletableFuture<String> shortTermFuture = CompletableFuture.supplyAsync(
                    () -> memoryService.getShortTermMemory(userId),
                    executor
            );

            CompletableFuture<String> longTermFuture = CompletableFuture.supplyAsync(
                    () -> {
                        // 长期记忆需要先 embedding 再搜索
                        List<Float> embedding = embeddingFactory.getEmbedding(query);
                        List<MemoryEntry> entries = memoryService.retrieve(userId, query, MemoryType.EPISODIC);
                        return formatMemoryContext(entries);
                    },
                    executor
            );

            // 并行获取偏好记忆
            CompletableFuture<String> preferenceFuture = CompletableFuture.supplyAsync(
                    () -> {
                        Map<String, Object> prefs = memoryService.getAllPreferences(userId);
                        return formatPreferenceContext(prefs);
                    },
                    executor
            );

            // 等待所有结果
            CompletableFuture.allOf(shortTermFuture, longTermFuture, preferenceFuture).join();

            String shortTermContext = shortTermFuture.get();
            String longTermContext = longTermFuture.get();
            String preferenceContext = preferenceFuture.get();

            long latency = System.currentTimeMillis() - startTime;
            log.debug("Parallel memory retrieval completed in {}ms", latency);

            return RAGContext.builder()
                    .shortTermMemory(shortTermContext)
                    .longTermMemory(longTermContext)
                    .preferenceContext(preferenceContext)
                    .retrievalLatencyMs(latency)
                    .build();

        } catch (Exception e) {
            log.error("Parallel retrieval failed, falling back to sequential", e);
            return fallbackRetrieve(userId, query);
        }
    }

    /**
     * 批量并行 RAG 查询
     * 多个查询同时 embedding + 搜索
     */
    public List<RAGResult> parallelBatchQuery(String userId, List<String> queries, int topK) {
        long startTime = System.currentTimeMillis();

        try {
            // 并行获取所有 query 的 embedding
            List<CompletableFuture<List<MemoryEntry>>> futures = queries.stream()
                    .map(query -> CompletableFuture.supplyAsync(
                            () -> memoryService.retrieve(userId, query, MemoryType.EPISODIC),
                            executor
                    ))
                    .collect(Collectors.toList());

            // 等待所有查询完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // 收集结果
            List<RAGResult> results = new ArrayList<>();
            for (int i = 0; i < queries.size(); i++) {
                try {
                    List<MemoryEntry> entries = futures.get(i).get();
                    results.add(new RAGResult(queries.get(i), entries));
                } catch (Exception e) {
                    log.error("Query {} failed: {}", i, e.getMessage());
                    results.add(new RAGResult(queries.get(i), Collections.emptyList()));
                }
            }

            long latency = System.currentTimeMillis() - startTime;
            log.debug("Parallel batch query {} queries completed in {}ms", queries.size(), latency);

            return results;

        } catch (Exception e) {
            log.error("Parallel batch query failed, falling back to sequential", e);
            return sequentialBatchQuery(userId, queries, topK);
        }
    }

    /**
     * 多 collection 并行搜索
     */
    public List<MemoryEntry> parallelMultiCollectionSearch(String userId, String query, List<MemoryType> types, int topK) {
        if (types == null || types.isEmpty()) {
            types = Arrays.asList(MemoryType.EPISODIC, MemoryType.FACTUAL, MemoryType.PREFERENCE);
        }

        long startTime = System.currentTimeMillis();

        try {
            // 并行搜索所有 collection
            List<CompletableFuture<List<MemoryEntry>>> futures = types.stream()
                    .map(type -> CompletableFuture.supplyAsync(
                            () -> memoryService.retrieve(userId, query, type),
                            executor
                    ))
                    .collect(Collectors.toList());

            // 等待所有搜索完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // 合并结果
            List<MemoryEntry> allEntries = new ArrayList<>();
            for (CompletableFuture<List<MemoryEntry>> future : futures) {
                try {
                    allEntries.addAll(future.get());
                } catch (Exception e) {
                    log.error("Collection search failed: {}", e.getMessage());
                }
            }

            // 按相关性排序并限制数量
            allEntries.sort((a, b) -> Double.compare(b.getImportance(), a.getImportance()));
            if (allEntries.size() > topK) {
                allEntries = allEntries.subList(0, topK);
            }

            long latency = System.currentTimeMillis() - startTime;
            log.debug("Parallel multi-collection search completed in {}ms", latency);

            return allEntries;

        } catch (Exception e) {
            log.error("Parallel multi-collection search failed", e);
            return sequentialMultiCollectionSearch(userId, query, types, topK);
        }
    }

    /**
     * 降级方案: 顺序执行
     */
    private RAGContext fallbackRetrieve(String userId, String query) {
        String shortTermContext = memoryService.getShortTermMemory(userId);

        List<MemoryEntry> longTermEntries = memoryService.retrieve(userId, query, MemoryType.EPISODIC);
        String longTermContext = formatMemoryContext(longTermEntries);

        Map<String, Object> prefs = memoryService.getAllPreferences(userId);
        String preferenceContext = formatPreferenceContext(prefs);

        return RAGContext.builder()
                .shortTermMemory(shortTermContext)
                .longTermMemory(longTermContext)
                .preferenceContext(preferenceContext)
                .retrievalLatencyMs(0)
                .build();
    }

    private List<RAGResult> sequentialBatchQuery(String userId, List<String> queries, int topK) {
        List<RAGResult> results = new ArrayList<>();
        for (String query : queries) {
            List<MemoryEntry> entries = memoryService.retrieve(userId, query, MemoryType.EPISODIC);
            results.add(new RAGResult(query, entries));
        }
        return results;
    }

    private List<MemoryEntry> sequentialMultiCollectionSearch(String userId, String query, List<MemoryType> types, int topK) {
        List<MemoryEntry> allEntries = new ArrayList<>();
        for (MemoryType type : types) {
            allEntries.addAll(memoryService.retrieve(userId, query, type));
        }
        allEntries.sort((a, b) -> Double.compare(b.getImportance(), a.getImportance()));
        if (allEntries.size() > topK) {
            return allEntries.subList(0, topK);
        }
        return allEntries;
    }

    /**
     * 格式化记忆上下文
     */
    private String formatMemoryContext(List<MemoryEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (MemoryEntry entry : entries) {
            sb.append("- ").append(entry.getContent()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 格式化偏好上下文
     */
    private String formatPreferenceContext(Map<String, Object> prefs) {
        if (prefs == null || prefs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("User Preferences:\n");
        prefs.forEach((key, value) -> sb.append("- ").append(key).append(": ").append(value).append("\n"));
        return sb.toString();
    }

    /**
     * RAG 上下文结果
     */
    @lombok.Data
    @lombok.Builder
    public static class RAGContext {
        private String shortTermMemory;
        private String longTermMemory;
        private String preferenceContext;
        private long retrievalLatencyMs;
    }

    /**
     * 单个 RAG 查询结果
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class RAGResult {
        private String query;
        private List<MemoryEntry> entries;
    }
}
