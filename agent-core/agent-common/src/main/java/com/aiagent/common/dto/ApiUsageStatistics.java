package com.aiagent.common.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * API 使用量统计
 */
@Data
@Builder
public class ApiUsageStatistics {
    private Long tenantId;
    private Long apiKeyId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private long totalRequests;
    private long totalInputTokens;
    private long totalOutputTokens;
    private long totalTokens;
    private long totalErrors;
    private long avgLatencyMs;
    private List<ApiUsageRecord> records;
}
