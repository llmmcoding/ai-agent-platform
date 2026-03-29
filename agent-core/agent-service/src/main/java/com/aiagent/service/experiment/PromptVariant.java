package com.aiagent.service.experiment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Prompt 变体定义
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptVariant {
    /**
     * 变体 ID
     */
    private String id;

    /**
     * Prompt 模板
     */
    private String promptTemplate;

    /**
     * 流量权重 (0.0 - 1.0)
     */
    private double trafficWeight;

    /**
     * 元数据
     */
    private Map<String, String> metadata;

    /**
     * 是否启用
     */
    private boolean enabled;
}
