package com.aiagent.service.chunk;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 切片配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkConfig {
    /**
     * 切片策略
     */
    @Builder.Default
    private String strategy = "recursive_character";

    /**
     * 切片大小 (字符数)
     */
    @Builder.Default
    private int chunkSize = 512;

    /**
     * 重叠大小 (字符数)
     */
    @Builder.Default
    private int overlap = 64;

    /**
     * 最小切片大小
     */
    @Builder.Default
    private int minChunkSize = 50;

    /**
     * 最大切片大小
     */
    @Builder.Default
    private int maxChunkSize = 1024;

    /**
     * 分割符 (按优先级排序)
     */
    @Builder.Default
    private List<String> separators = List.of("\n\n", "\n", ". ", " ", "");

    /**
     * 是否保留分隔符
     */
    @Builder.Default
    private boolean keepSeparator = true;

    /**
     * 是否返回元数据
     */
    @Builder.Default
    private boolean returnMetadata = true;

    /**
     * 文档 ID 前缀
     */
    @Builder.Default
    private String documentIdPrefix = "doc";

    /**
     * 标题提取模式 (用于保持文档结构)
     */
    @Builder.Default
    private String titlePattern = "^#\\s+(.+)$";
}
