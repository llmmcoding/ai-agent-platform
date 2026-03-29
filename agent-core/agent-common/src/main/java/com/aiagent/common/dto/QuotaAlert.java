package com.aiagent.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 配额告警
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuotaAlert {
    private Long id;
    private Long tenantId;
    private Long apiKeyId;
    private String alertType;       // 'RPM_WARNING', 'RPM_EXCEEDED', 'TPM_WARNING', 'TPM_EXCEEDED'
    private Long thresholdValue;
    private Long actualValue;
    private LocalDateTime triggeredAt;
    private Boolean acknowledged;
    private LocalDateTime acknowledgedAt;
    private String acknowledgedBy;
}
