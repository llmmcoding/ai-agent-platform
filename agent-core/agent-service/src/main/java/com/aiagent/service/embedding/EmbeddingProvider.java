package com.aiagent.service.embedding;

import java.util.List;

/**
 * Embedding 提供者接口
 * 支持多种 Embedding 实现: Jina, OpenAI, 本地模型等
 */
public interface EmbeddingProvider {

    /**
     * 获取文本的 embedding 向量
     *
     * @param text 文本
     * @return embedding 向量
     */
    List<Float> getEmbedding(String text);

    /**
     * 批量获取 embedding
     *
     * @param texts 文本列表
     * @return embedding 列表
     */
    default List<List<Float>> getEmbeddings(List<String> texts) {
        return texts.stream().map(this::getEmbedding).toList();
    }

    /**
     * 获取向量维度
     */
    int getDimension();

    /**
     * 提供者名称
     */
    String getName();

    /**
     * 健康检查
     */
    default boolean isHealthy() {
        return true;
    }
}
