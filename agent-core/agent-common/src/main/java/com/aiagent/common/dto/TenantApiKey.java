package com.aiagent.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 租户 API Key 实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantApiKey {
    private Long id;              // Primary key
    private Long tenantId;        // 租户 ID (FK)
    private String keyHash;       // SHA-256 哈希
    private String keyAlias;      // 显示别名 (sk-xxxx...xxxx)
    private String userId;        // 用户标识
    private Long rpmLimit;        // Requests Per Minute (0 = 无限制)
    private Long tpmLimit;        // Tokens Per Minute (0 = 无限制)
    private String allowedTools;  // 允许的工具列表 (逗号分隔，空=全部)
    private Boolean isActive;     // 是否启用
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt; // 过期时间 (可选，NULL=永久)
}
