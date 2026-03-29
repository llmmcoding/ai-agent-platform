package com.aiagent.common.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * API 使用量记录
 */
@Data
@Builder
public class ApiUsageRecord {
    private Long id;
    private Long tenantId;
    private Long apiKeyId;
    private LocalDateTime recordHour;
    private Long requestCount;
    private Long inputTokens;
    private Long outputTokens;
    private Long totalTokens;
    private Long errorCount;
    private Long totalLatencyMs;
    private Long avgLatencyMs;
}
