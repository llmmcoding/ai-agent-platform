package com.aiagent.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 租户实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Tenant {
    private Long id;
    private String name;              // 租户名称
    private String code;              // 租户编码 (唯一标识)
    private Long rpmLimit;           // 租户级别 RPM (所有 Key 聚合)
    private Long tpmLimit;           // 租户级别 TPM
    private Long maxConcurrentRequests; // 最大并发请求数
    private Map<String, String> metadata; // 扩展配置 (JSON)
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
