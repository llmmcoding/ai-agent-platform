package com.aiagent.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前配额使用情况
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuotaUsage {
    private Long tenantId;
    private Long apiKeyId;

    // Key 级别
    private Long keyRpmLimit;
    private Long keyRpmUsed;
    private Double keyRpmPercent;

    private Long keyTpmLimit;
    private Long keyTpmUsed;
    private Double keyTpmPercent;

    // Tenant 级别
    private Long tenantRpmLimit;
    private Long tenantRpmUsed;
    private Double tenantRpmPercent;

    private Long tenantTpmLimit;
    private Long tenantTpmUsed;
    private Double tenantTpmPercent;
}
