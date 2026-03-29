package com.aiagent.service.chunk;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 切片管理器
 * 统一管理多种切片策略
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkManager {

    private final List<TextChunker> chunkers;

    @Value("${aiagent.chunker.default-strategy:recursive_character}")
    private String defaultStrategy;

    private final Map<String, TextChunker> chunkerMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        for (TextChunker chunker : chunkers) {
            chunkerMap.put(chunker.getName(), chunker);
        }
        log.info("ChunkManager initialized with {} strategies: {}",
                chunkers.size(), chunkerMap.keySet());
    }

    /**
     * 切片文本
     */
    public List<Chunk> chunk(String text, ChunkConfig config) {
        TextChunker chunker = chunkerMap.getOrDefault(
                config.getStrategy() != null ? config.getStrategy() : defaultStrategy,
                chunkerMap.get("recursive_character")
        );

        if (chunker == null) {
            log.warn("No chunker found for strategy {}, using RecursiveCharacterChunker", config.getStrategy());
            chunker = chunkerMap.get("recursive_character");
        }

        return chunker.chunk(text, config);
    }

    /**
     * 切片文本 (使用默认策略)
     */
    public List<Chunk> chunk(String text) {
        return chunk(text, ChunkConfig.builder().build());
    }

    /**
     * 切片文本 (使用指定策略)
     */
    public List<Chunk> chunk(String text, String strategy) {
        ChunkConfig config = ChunkConfig.builder()
                .strategy(strategy)
                .build();
        return chunk(text, config);
    }

    /**
     * 获取可用的切片策略
     */
    public List<String> getAvailableStrategies() {
        return List.copyOf(chunkerMap.keySet());
    }

    /**
     * 获取默认策略
     */
    public String getDefaultStrategy() {
        return defaultStrategy;
    }
}
