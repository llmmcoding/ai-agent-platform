package com.aiagent.service.controller;

import com.aiagent.common.Result;
import com.aiagent.service.vector.VectorStoreFactory;
import com.aiagent.service.vector.VectorStoreFactory.VectorSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * RAG API 控制器
 * 负责向量检索，支持 Milvus/pgvector 切换
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/rag")
@Tag(name = "RAG", description = "RAG 向量检索接口")
@RequiredArgsConstructor
public class RagController {

    private final VectorStoreFactory vectorStoreFactory;

    @Value("${aiagent.vector-store.collection.default:default}")
    private String defaultCollection;

    @Value("${aiagent.vector-store.search.top-k:20}")
    private int defaultTopK;

    /**
     * 向量检索
     * 由 Python 侧调用（传入 embedding 向量）
     */
    @PostMapping("/search")
    @Operation(summary = "向量检索", description = "接收 embedding 向量，执行向量数据库 ANN 搜索")
    public Mono<Result<List<Map<String, Object>>>> search(
            @RequestBody RagSearchRequest request) {

        String collection = request.getCollection() != null ?
                request.getCollection() : defaultCollection;
        int topK = request.getTopK() > 0 ? request.getTopK() : defaultTopK;

        log.info("RAG search - provider: {}, collection: {}, topK: {}, embedding dim: {}",
                vectorStoreFactory.getCurrentProvider(),
                collection, topK,
                request.getQueryEmbedding() != null ? request.getQueryEmbedding().size() : 0);

        try {
            VectorSearchService searchService = vectorStoreFactory.getSearchService();
            List<Map<String, Object>> results = searchService.searchVectors(
                    request.getQueryEmbedding(),
                    collection,
                    topK
            );

            return Mono.just(Result.success(results));
        } catch (Exception e) {
            log.error("RAG search failed: {}", e.getMessage(), e);
            return Mono.just(Result.error("Search failed: " + e.getMessage()));
        }
    }

    /**
     * 获取 Collection 列表
     */
    @GetMapping("/collections")
    @Operation(summary = "获取 Collection 列表")
    public Mono<Result<List<String>>> listCollections() {
        return Mono.just(Result.success(List.of(defaultCollection)));
    }

    /**
     * 创建 Collection
     */
    @PostMapping("/collections/{name}")
    @Operation(summary = "创建 Collection")
    public Mono<Result<String>> createCollection(@PathVariable String name) {
        try {
            VectorSearchService searchService = vectorStoreFactory.getSearchService();
            searchService.createCollection(name);
            return Mono.just(Result.success("Collection created: " + name));
        } catch (Exception e) {
            log.error("Failed to create collection: {}", e.getMessage(), e);
            return Mono.just(Result.error("Failed to create collection: " + e.getMessage()));
        }
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    @Operation(summary = "RAG 健康检查")
    public Mono<Result<Map<String, Object>>> health() {
        VectorSearchService searchService = vectorStoreFactory.getSearchService();
        Map<String, Object> status = Map.of(
                "status", "healthy",
                "provider", vectorStoreFactory.getCurrentProvider(),
                "defaultCollection", defaultCollection
        );
        return Mono.just(Result.success(status));
    }

    /**
     * RAG 检索请求
     */
    @lombok.Data
    public static class RagSearchRequest {
        private List<Float> queryEmbedding;  // embedding 向量
        private String collection;            // 集合名称
        private Integer topK;               // 返回数量
    }
}
