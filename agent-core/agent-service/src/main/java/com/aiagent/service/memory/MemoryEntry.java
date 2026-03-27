package com.aiagent.service.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 记忆条目
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryEntry {

    /**
     * 唯一 ID
     */
    private String id;

    /**
     * 记忆类型
     */
    private MemoryType type;

    /**
     * 所属会话 ID (情景记忆用)
     */
    private String sessionId;

    /**
     * 所属用户 ID
     */
    private String userId;

    /**
     * 记忆内容
     */
    private String content;

    /**
     * 实体标识 (事实记忆用)
     * 例如: "用户公司", "用户职位"
     */
    private String entityKey;

    /**
     * 实体值 (事实记忆用)
     */
    private String entityValue;

    /**
     * 元数据
     */
    private Map<String, Object> metadata;

    /**
     * 重要性评分 (0.0 - 1.0)
     */
    private double importance;

    /**
     * 创建时间戳
     */
    private long timestamp;

    /**
     * 最后访问时间
     */
    private long lastAccessedAt;

    /**
     * 创建时间 (静态工厂)
     */
    public static MemoryEntry create(MemoryType type, String userId, String content) {
        return MemoryEntry.builder()
                .id(java.util.UUID.randomUUID().toString())
                .type(type)
                .userId(userId)
                .content(content)
                .importance(0.5)
                .timestamp(System.currentTimeMillis())
                .lastAccessedAt(System.currentTimeMillis())
                .build();
    }
}
