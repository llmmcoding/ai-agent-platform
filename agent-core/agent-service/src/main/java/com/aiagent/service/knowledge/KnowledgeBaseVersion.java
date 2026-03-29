package com.aiagent.service.knowledge;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 知识库版本
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBaseVersion {
    /**
     * 版本 ID
     */
    private String id;

    /**
     * Collection 名称
     */
    private String collection;

    /**
     * 版本号
     */
    private int version;

    /**
     * 文档数量
     */
    private long documentCount;

    /**
     * 状态: CREATING, ACTIVE, DEPRECATED, DELETING
     */
    private String status;

    /**
     * 创建时间
     */
    private Instant createdAt;

    /**
     * 激活时间
     */
    private Instant activatedAt;

    /**
     * 描述
     */
    private String description;

    /**
     * 元数据
     */
    private String metadata;
}
