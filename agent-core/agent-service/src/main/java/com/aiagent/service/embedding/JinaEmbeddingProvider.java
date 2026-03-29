package com.aiagent.service.embedding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * Jina AI Embedding 提供者
 * 使用 Jina AI API 获取文本向量
 * 文档: https://jina.ai/embeddings/
 */
@Slf4j
@Component
public class JinaEmbeddingProvider implements EmbeddingProvider {

    private final WebClient webClient;

    @Value("${aiagent.embedding.jina.api-key:}")
    private String apiKey;

    @Value("${aiagent.embedding.jina.base-url:https://api.jina.ai}")
    private String baseUrl;

    @Value("${aiagent.embedding.jina.model:jina-embeddings-v3}")
    private String model;

    @Value("${aiagent.embedding.jina.dimension:1024}")
    private int dimension;

    @Value("${aiagent.embedding.jina.task:auto}")
    private String task;

    private static final int DEFAULT_DIMENSION = 1024;

    public JinaEmbeddingProvider(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public List<Float> getEmbedding(String text) {
        try {
            JinaResponse response = webClient.post()
                    .uri(baseUrl + "/v1/embeddings")
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(JinaRequest.builder()
                            .model(model)
                            .task(task)
                            .dimensions(dimension)
                            .input(List.of(text))
                            .build())
                    .retrieve()
                    .bodyToMono(JinaResponse.class)
                    .block();

            if (response != null && response.getData() != null && !response.getData().isEmpty()) {
                log.debug("Jina embedding success, dimension: {}", dimension);
                return response.getData().get(0).getEmbedding();
            }

            log.warn("Jina embedding returned empty, using fallback");
            return getFallbackEmbedding(text);

        } catch (Exception e) {
            log.error("Jina embedding failed: {}", e.getMessage());
            return getFallbackEmbedding(text);
        }
    }

    @Override
    public List<List<Float>> getEmbeddings(List<String> texts) {
        try {
            JinaResponse response = webClient.post()
                    .uri(baseUrl + "/v1/embeddings")
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(JinaRequest.builder()
                            .model(model)
                            .task(task)
                            .dimensions(dimension)
                            .input(texts)
                            .build())
                    .retrieve()
                    .bodyToMono(JinaResponse.class)
                    .block();

            if (response != null && response.getData() != null) {
                return response.getData().stream()
                        .map(JinaResponse.Data::getEmbedding)
                        .toList();
            }

            return texts.stream().map(this::getFallbackEmbedding).toList();

        } catch (Exception e) {
            log.error("Jina batch embedding failed: {}", e.getMessage());
            return texts.stream().map(this::getFallbackEmbedding).toList();
        }
    }

    @Override
    public int getDimension() {
        return dimension > 0 ? dimension : DEFAULT_DIMENSION;
    }

    @Override
    public String getName() {
        return "jina";
    }

    @Override
    public boolean isHealthy() {
        try {
            webClient.post()
                    .uri(baseUrl + "/v1/embeddings")
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(JinaRequest.builder()
                            .model(model)
                            .input(List.of("health check"))
                            .build())
                    .retrieve()
                    .bodyToMono(JinaResponse.class)
                    .block();
            return true;
        } catch (Exception e) {
            log.warn("Jina health check failed: {}", e.getMessage());
            return false;
        }
    }

    private List<Float> getFallbackEmbedding(String text) {
        // 返回基于文本特征的伪向量 (用于服务降级)
        // 实际生产应配置备用 provider
        log.warn("Using fallback embedding for text: {}...", text.substring(0, Math.min(50, text.length())));
        return new java.util.ArrayList<>(java.util.Collections.nCopies(getDimension(), 0.0f));
    }

    // ==================== Jina API DTO ====================

    @lombok.Data
    @lombok.Builder
    private static class JinaRequest {
        private String model;
        private String task;
        private Integer dimensions;
        private List<String> input;
    }

    @lombok.Data
    private static class JinaResponse {
        private String model;
        private String task;
        private String usage;
        private List<Data> data;

        @lombok.Data
        private static class Data {
            private String index;
            private List<Float> embedding;
            private String object;
        }
    }
}
