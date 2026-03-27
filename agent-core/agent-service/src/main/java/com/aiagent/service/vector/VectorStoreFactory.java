package com.aiagent.service.vector;

import com.aiagent.service.milvus.MilvusService;
import com.aiagent.service.pgvector.PGVectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 向量存储工厂
 * 支持 Milvus 和 pgvector 切换
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorStoreFactory {

    private final MilvusService milvusService;
    private final PGVectorService pgVectorService;

    @Value("${aiagent.vector-store.provider:milvus}")
    private String vectorStoreProvider;

    private String currentProvider;

    @PostConstruct
    public void init() {
        currentProvider = vectorStoreProvider.toLowerCase();
        log.info("VectorStoreFactory initialized with provider: {}", currentProvider);
    }

    /**
     * 获取向量检索服务
     */
    public VectorSearchService getSearchService() {
        switch (currentProvider) {
            case "pgvector":
                return new PGVectorSearchAdapter(pgVectorService);
            case "milvus":
            default:
                return new MilvusSearchAdapter(milvusService);
        }
    }

    /**
     * 获取当前 provider 名称
     */
    public String getCurrentProvider() {
        return currentProvider;
    }

    /**
     * 向量搜索服务接口
     */
    public interface VectorSearchService {
        /**
         * 搜索向量
         */
        java.util.List<Map<String, Object>> searchVectors(java.util.List<Float> queryEmbedding,
                                                           String collection, int topK);

        /**
         * 插入向量
         */
        int insertVectors(String collection,
                         java.util.List<Map<String, Object>> documents,
                         java.util.List<java.util.List<Float>> embeddings);

        /**
         * 删除向量
         */
        boolean deleteVectors(String collection, java.util.List<String> ids);

        /**
         * 创建 Collection
         */
        void createCollection(String collection);

        /**
         * Collection 是否存在
         */
        boolean collectionExists(String collection);
    }

    /**
     * Milvus 搜索适配器
     */
    private static class MilvusSearchAdapter implements VectorSearchService {
        private final MilvusService milvusService;

        public MilvusSearchAdapter(MilvusService milvusService) {
            this.milvusService = milvusService;
        }

        @Override
        public java.util.List<Map<String, Object>> searchVectors(java.util.List<Float> queryEmbedding,
                                                                 String collection, int topK) {
            return milvusService.searchVectors(queryEmbedding, collection, topK);
        }

        @Override
        public int insertVectors(String collection,
                                 java.util.List<Map<String, Object>> documents,
                                 java.util.List<java.util.List<Float>> embeddings) {
            return milvusService.insertVectors(collection, documents, embeddings);
        }

        @Override
        public boolean deleteVectors(String collection, java.util.List<String> ids) {
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
     * pgvector 搜索适配器
     */
    private static class PGVectorSearchAdapter implements VectorSearchService {
        private final PGVectorService pgVectorService;

        public PGVectorSearchAdapter(PGVectorService pgVectorService) {
            this.pgVectorService = pgVectorService;
        }

        @Override
        public java.util.List<Map<String, Object>> searchVectors(java.util.List<Float> queryEmbedding,
                                                                 String collection, int topK) {
            return pgVectorService.searchVectors(queryEmbedding, collection, topK);
        }

        @Override
        public int insertVectors(String collection,
                                 java.util.List<Map<String, Object>> documents,
                                 java.util.List<java.util.List<Float>> embeddings) {
            return pgVectorService.insertVectors(collection, documents, embeddings);
        }

        @Override
        public boolean deleteVectors(String collection, java.util.List<String> ids) {
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
