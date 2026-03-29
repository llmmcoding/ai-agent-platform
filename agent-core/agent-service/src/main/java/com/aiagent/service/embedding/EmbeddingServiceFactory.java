package com.aiagent.service.embedding;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedding 服务工厂
 * 根据配置选择不同的 Embedding 提供者
 * 支持: jina, openai, local
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingServiceFactory {

    private final JinaEmbeddingProvider jinaProvider;
    private final OpenAIEmbeddingProvider openaiProvider;

    @Value("${aiagent.embedding.provider:jina}")
    private String providerName;

    @Value("${aiagent.embedding.fallback-provider:openai}")
    private String fallbackProviderName;

    @Value("${aiagent.embedding.dimension:1536}")
    private int defaultDimension;

    private EmbeddingProvider currentProvider;
    private EmbeddingProvider fallbackProvider;

    // 提供者注册表
    private static final Map<String, EmbeddingProvider> PROVIDERS = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 注册提供者
        PROVIDERS.put("jina", jinaProvider);
        PROVIDERS.put("openai", openaiProvider);

        // 选择主提供者
        currentProvider = PROVIDERS.getOrDefault(providerName.toLowerCase(), jinaProvider);

        // 选择备用提供者
        fallbackProvider = PROVIDERS.getOrDefault(fallbackProviderName.toLowerCase(), openaiProvider);

        // 检查主提供者健康状态
        if (!currentProvider.isHealthy()) {
            log.warn("Primary embedding provider '{}' is unhealthy, switching to fallback '{}'",
                    currentProvider.getName(), fallbackProvider.getName());
            EmbeddingProvider temp = currentProvider;
            currentProvider = fallbackProvider;
            fallbackProvider = temp;
        }

        log.info("Embedding service initialized: primary={}, fallback={}, dimension={}",
                currentProvider.getName(), fallbackProvider.getName(), currentProvider.getDimension());
    }

    /**
     * 获取当前激活的 Embedding 提供者
     */
    public EmbeddingProvider getProvider() {
        return currentProvider;
    }

    /**
     * 获取 embedding 向量 (带自动降级)
     *
     * @param text 文本
     * @return embedding 向量
     */
    public List<Float> getEmbedding(String text) {
        try {
            return currentProvider.getEmbedding(text);
        } catch (Exception e) {
            log.warn("Primary provider '{}' failed, trying fallback: {}",
                    currentProvider.getName(), e.getMessage());
            try {
                return fallbackProvider.getEmbedding(text);
            } catch (Exception fallbackError) {
                log.error("Fallback provider '{}' also failed: {}",
                        fallbackProvider.getName(), fallbackError.getMessage());
                return getFallbackEmbedding(text);
            }
        }
    }

    /**
     * 批量获取 embedding (带自动降级)
     */
    public List<List<Float>> getEmbeddings(List<String> texts) {
        try {
            return currentProvider.getEmbeddings(texts);
        } catch (Exception e) {
            log.warn("Primary provider '{}' failed for batch, trying fallback: {}",
                    currentProvider.getName(), e.getMessage());
            try {
                return fallbackProvider.getEmbeddings(texts);
            } catch (Exception fallbackError) {
                log.error("Fallback provider '{}' also failed: {}",
                        fallbackProvider.getName(), fallbackError.getMessage());
                return texts.stream().map(this::getFallbackEmbedding).toList();
            }
        }
    }

    /**
     * 获取向量维度
     */
    public int getDimension() {
        return currentProvider.getDimension();
    }

    /**
     * 获取所有可用的提供者
     */
    public Map<String, EmbeddingProvider> getAllProviders() {
        return Map.copyOf(PROVIDERS);
    }

    /**
     * 检查提供者健康状态
     */
    public Map<String, Boolean> getHealthStatus() {
        return Map.of(
                currentProvider.getName(), currentProvider.isHealthy(),
                fallbackProvider.getName(), fallbackProvider.isHealthy()
        );
    }

    /**
     * 切换到指定提供者
     */
    public void switchProvider(String providerName) {
        EmbeddingProvider newProvider = PROVIDERS.get(providerName.toLowerCase());
        if (newProvider != null) {
            currentProvider = newProvider;
            log.info("Switched to embedding provider: {}", providerName);
        } else {
            log.warn("Unknown provider: {}, keeping current: {}",
                    providerName, currentProvider.getName());
        }
    }

    /**
     * 获取 fallback 向量 (零向量)
     */
    private List<Float> getFallbackEmbedding(String text) {
        log.error("All embedding providers failed, returning zero vector for text: {}...",
                text.substring(0, Math.min(30, text.length())));
        return new java.util.ArrayList<>(java.util.Collections.nCopies(defaultDimension, 0.0f));
    }
}
