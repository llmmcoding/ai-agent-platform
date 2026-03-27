package com.aiagent.service.embedding;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Embedding 服务
 * 调用 Python Worker 的 Embedding API 获取向量
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final WebClient webClient;

    @Value("${aiagent.python-worker.url:http://localhost:8001}")
    private String pythonWorkerUrl;

    /**
     * 获取文本的 embedding 向量
     *
     * @param text 文本
     * @return embedding 向量
     */
    public List<Float> getEmbedding(String text) {
        try {
            EmbeddingResponse response = webClient.post()
                    .uri(pythonWorkerUrl + "/api/v1/embedding")
                    .bodyValue(new EmbeddingRequest(text))
                    .retrieve()
                    .bodyToMono(EmbeddingResponse.class)
                    .block();

            if (response != null && response.isSuccess() && response.getEmbedding() != null) {
                log.debug("Got embedding, dim: {}", response.getEmbedding().size());
                return response.getEmbedding();
            }

            log.warn("Embedding request failed or returned empty, using fallback");
            return getFallbackEmbedding(text);

        } catch (Exception e) {
            log.error("Failed to get embedding from Python service: {}", e.getMessage());
            return getFallbackEmbedding(text);
        }
    }

    /**
     * 异步获取 embedding
     */
    public CompletableFuture<List<Float>> getEmbeddingAsync(String text) {
        return CompletableFuture.supplyAsync(() -> getEmbedding(text));
    }

    /**
     * 批量获取 embedding
     *
     * @param texts 文本列表
     * @return embedding 列表
     */
    public List<List<Float>> getEmbeddings(List<String> texts) {
        return texts.stream()
                .map(this::getEmbedding)
                .toList();
    }

    /**
     * 获取 fallback 向量 (当 Python 服务不可用时)
     * 注意: 这只是占位符，实际应该避免使用
     */
    private List<Float> getFallbackEmbedding(String text) {
        // 返回零向量作为 fallback
        // 实际生产环境应该使用本地 embedding 模型
        log.warn("Using fallback zero embedding - this should not happen in production");
        return new java.util.ArrayList<>(java.util.Collections.nCopies(1536, 0.0f));
    }

    /**
     * 获取向量维度
     */
    public int getDimension() {
        return 1536;  // OpenAI text-embedding-3-small
    }

    // ==================== 请求/响应 DTO ====================

    @lombok.Data
    public static class EmbeddingRequest {
        private String text;

        public EmbeddingRequest() {}

        public EmbeddingRequest(String text) {
            this.text = text;
        }
    }

    @lombok.Data
    public static class EmbeddingResponse {
        private boolean success;
        private List<Float> embedding;
        private String provider;
        private String error;

        public boolean isSuccess() {
            return success;
        }
    }
}
