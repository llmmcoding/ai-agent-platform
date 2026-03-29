package com.aiagent.service.embedding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * OpenAI Embedding 提供者
 * 使用 OpenAI API 获取文本向量
 * 文档: https://platform.openai.com/docs/guides/embeddings
 */
@Slf4j
@Component
public class OpenAIEmbeddingProvider implements EmbeddingProvider {

    private final WebClient webClient;

    @Value("${aiagent.embedding.openai.api-key:}")
    private String apiKey;

    @Value("${aiagent.embedding.openai.base-url:https://api.openai.com}")
    private String baseUrl;

    @Value("${aiagent.embedding.openai.model:text-embedding-3-small}")
    private String model;

    @Value("${aiagent.embedding.openai.dimension:1536}")
    private int dimension;

    private static final int DEFAULT_DIMENSION = 1536;

    public OpenAIEmbeddingProvider(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public List<Float> getEmbedding(String text) {
        try {
            OpenAIResponse response = webClient.post()
                    .uri(baseUrl + "/v1/embeddings")
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(OpenAIRequest.builder()
                            .model(model)
                            .input(text)
                            .dimensions(dimension)
                            .build())
                    .retrieve()
                    .bodyToMono(OpenAIResponse.class)
                    .block();

            if (response != null && response.getData() != null && !response.getData().isEmpty()) {
                log.debug("OpenAI embedding success, dimension: {}", dimension);
                return response.getData().get(0).getEmbedding();
            }

            log.warn("OpenAI embedding returned empty, using fallback");
            return getFallbackEmbedding(text);

        } catch (Exception e) {
            log.error("OpenAI embedding failed: {}", e.getMessage());
            return getFallbackEmbedding(text);
        }
    }

    @Override
    public List<List<Float>> getEmbeddings(List<String> texts) {
        try {
            OpenAIResponse response = webClient.post()
                    .uri(baseUrl + "/v1/embeddings")
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(OpenAIRequest.builder()
                            .model(model)
                            .input(texts)
                            .dimensions(dimension)
                            .build())
                    .retrieve()
                    .bodyToMono(OpenAIResponse.class)
                    .block();

            if (response != null && response.getData() != null) {
                return response.getData().stream()
                        .sorted((a, b) -> Integer.compare(a.getIndex(), b.getIndex()))
                        .map(OpenAIResponse.Data::getEmbedding)
                        .toList();
            }

            return texts.stream().map(this::getFallbackEmbedding).toList();

        } catch (Exception e) {
            log.error("OpenAI batch embedding failed: {}", e.getMessage());
            return texts.stream().map(this::getFallbackEmbedding).toList();
        }
    }

    @Override
    public int getDimension() {
        return dimension > 0 ? dimension : DEFAULT_DIMENSION;
    }

    @Override
    public String getName() {
        return "openai";
    }

    @Override
    public boolean isHealthy() {
        try {
            webClient.post()
                    .uri(baseUrl + "/v1/embeddings")
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(OpenAIRequest.builder()
                            .model(model)
                            .input("health check")
                            .build())
                    .retrieve()
                    .bodyToMono(OpenAIResponse.class)
                    .block();
            return true;
        } catch (Exception e) {
            log.warn("OpenAI health check failed: {}", e.getMessage());
            return false;
        }
    }

    private List<Float> getFallbackEmbedding(String text) {
        log.warn("Using fallback embedding for text: {}...", text.substring(0, Math.min(50, text.length())));
        return new java.util.ArrayList<>(java.util.Collections.nCopies(getDimension(), 0.0f));
    }

    // ==================== OpenAI API DTO ====================

    @lombok.Data
    @lombok.Builder
    private static class OpenAIRequest {
        private String model;
        private Object input; // String or List<String>
        private Integer dimensions;
    }

    @lombok.Data
    private static class OpenAIResponse {
        private String model;
        private String object;
        private List<Data> data;
        private Usage usage;

        @lombok.Data
        private static class Data {
            private String object;
            private int index;
            private List<Float> embedding;
        }

        @lombok.Data
        private static class Usage {
            private int promptTokens;
            private int totalTokens;
        }
    }
}
