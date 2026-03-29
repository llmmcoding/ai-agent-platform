package com.aiagent.service.tenant;

import com.aiagent.common.dto.Tenant;
import com.aiagent.common.dto.TenantApiKey;
import com.aiagent.common.dto.TenantContext;

import java.util.List;

/**
 * 租户 API Key 管理服务接口
 */
public interface TenantApiKeyService {

    // ==================== 租户管理 ====================

    /**
     * 创建租户
     */
    Tenant createTenant(Tenant tenant);

    /**
     * 获取租户
     */
    Tenant getTenant(Long tenantId);

    /**
     * 获取租户 By Code
     */
    Tenant getTenantByCode(String code);

    /**
     * 更新租户
     */
    Tenant updateTenant(Long tenantId, Tenant tenant);

    /**
     * 停用租户
     */
    void deactivateTenant(Long tenantId);

    /**
     * 获取所有租户
     */
    List<Tenant> getAllTenants();

    // ==================== API Key 管理 ====================

    /**
     * 生成租户 API Key
     *
     * @param tenantId 租户 ID
     * @param userId 用户标识
     * @param rpmLimit RPM 限制 (0=无限制)
     * @param tpmLimit TPM 限制 (0=无限制)
     * @param allowedTools 允许的工具列表 (空=全部)
     * @param expiresAt 过期时间 (null=永久)
     * @return 生成的 Key 信息 (包含原始 Key，仅此时返回一次)
     */
    ApiKeyGenerateResult generateApiKey(Long tenantId, String userId,
                                        Long rpmLimit, Long tpmLimit,
                                        String allowedTools,
                                        java.time.LocalDateTime expiresAt);

    /**
     * 验证 API Key
     *
     * @param rawApiKey 原始 API Key
     * @return 租户上下文，验证失败返回 null
     */
    TenantContext validateApiKey(String rawApiKey);

    /**
     * 停用 API Key
     */
    void deactivateApiKey(Long keyId);

    /**
     * 获取租户所有 Key
     */
    List<TenantApiKey> getKeysByTenant(Long tenantId);

    /**
     * 获取 Key 信息 (不含原始 Key)
     */
    TenantApiKey getKeyById(Long keyId);

    /**
     * 轮转 API Key
     * 生成新 Key，保留旧 Key 宽限期
     *
     * @param keyId 要轮转的 Key ID
     * @param gracePeriodHours 旧 Key 宽限期 (小时)
     * @return 轮转结果 (包含新 Key)
     */
    ApiKeyRotateResult rotateApiKey(Long keyId, int gracePeriodHours);

    /**
     * 更新 API Key 元数据
     */
    void updateApiKey(Long keyId, Long rpmLimit, Long tpmLimit, String allowedTools);

    // ==================== 内部方法 ====================

    /**
     * 检查平台管理员 Key 是否有效
     */
    boolean validatePlatformKey(String rawApiKey);

    /**
     * API Key 生成结果
     */
    record ApiKeyGenerateResult(
            Long keyId,
            String rawKey,           // 仅创建时返回
            String keyAlias,
            Long tenantId,
            String userId,
            Long rpmLimit,
            Long tpmLimit,
            String allowedTools,
            java.time.LocalDateTime createdAt,
            java.time.LocalDateTime expiresAt
    ) {}

    /**
     * API Key 轮转结果
     */
    record ApiKeyRotateResult(
            Long newKeyId,
            String newRawKey,        // 仅此时返回
            String newKeyAlias,
            java.time.LocalDateTime oldKeyExpiresAt  // 旧 Key 过期时间
    ) {}
}
