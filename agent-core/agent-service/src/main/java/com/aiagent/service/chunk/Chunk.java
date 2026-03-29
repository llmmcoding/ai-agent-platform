package com.aiagent.service.chunk;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 文档切片
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Chunk {
    /**
     * 切片 ID
     */
    private String id;

    /**
     * 切片内容
     */
    private String content;

    /**
     * 切片元数据
     */
    private Map<String, Object> metadata;

    /**
     * 起始位置
     */
    private int startPosition;

    /**
     * 结束位置
     */
    private int endPosition;

    /**
     * 切片序号
     */
    private int chunkIndex;

    /**
     * 所属文档 ID
     */
    private String documentId;

    /**
     * 切片长度
     */
    public int getLength() {
        return content != null ? content.length() : 0;
    }
}
