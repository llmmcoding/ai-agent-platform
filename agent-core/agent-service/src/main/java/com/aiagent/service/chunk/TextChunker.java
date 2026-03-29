package com.aiagent.service.chunk;

import java.util.List;

/**
 * 文本切片器接口
 */
public interface TextChunker {

    /**
     * 切片文本
     *
     * @param text  原始文本
     * @param config 切片配置
     * @return 切片列表
     */
    List<Chunk> chunk(String text, ChunkConfig config);

    /**
     * 切片文本 (使用默认配置)
     *
     * @param text 原始文本
     * @return 切片列表
     */
    default List<Chunk> chunk(String text) {
        return chunk(text, ChunkConfig.builder().build());
    }

    /**
     * 获取切片器名称
     */
    String getName();
}
