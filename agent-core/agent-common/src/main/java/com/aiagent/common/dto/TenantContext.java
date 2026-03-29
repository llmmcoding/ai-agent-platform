package com.aiagent.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 租户上下文 (请求级别，ThreadLocal 存储)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantContext {
    private Long tenantId;           // 租户 ID
    private String tenantCode;       // 租户编码
    private Long apiKeyId;           // API Key ID
    private String userId;           // 用户 ID
    private String apiKeyAlias;      // Key 别名
    private Long rpmLimit;           // 该 Key 的 RPM (0=无限制)
    private Long tpmLimit;           // 该 Key 的 TPM
    private List<String> allowedTools; // 允许的工具

    // 租户级别限制
    private Long tenantRpmLimit;    // 租户聚合 RPM
    private Long tenantTpmLimit;    // 租户聚合 TPM

    /**
     * 检查是否允许使用指定工具
     */
    public boolean isToolAllowed(String toolName) {
        if (allowedTools == null || allowedTools.isEmpty()) {
            return true; // 空列表表示允许所有工具
        }
        return allowedTools.contains(toolName);
    }

    /**
     * 检查 Key 是否已过期
     */
    public boolean isExpired() {
        if (expiresAt == null) {
            return false; // NULL = 永久有效
        }
        return expiresAt.isBefore(java.time.LocalDateTime.now());
    }

    private java.time.LocalDateTime expiresAt; // 过期时间
}
