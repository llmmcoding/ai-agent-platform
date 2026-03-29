package com.aiagent.service.tenant;

import com.aiagent.common.dto.Tenant;
import com.aiagent.common.dto.TenantApiKey;
import com.aiagent.common.dto.TenantContext;
import com.aiagent.service.audit.AuditEventType;
import com.aiagent.service.audit.AuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 租户 API Key 服务实现
 * 存储: MySQL 持久化 + Redis 缓存
 */
@Slf4j
@Service
public class TenantApiKeyServiceImpl implements TenantApiKeyService {

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    private static final String API_KEY_CACHE_PREFIX = "aiagent:apikey:";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public TenantApiKeyServiceImpl(JdbcTemplate jdbcTemplate,
                                 StringRedisTemplate redisTemplate,
                                 ObjectMapper objectMapper,
                                 @Autowired(required = false) AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    // ==================== 租户管理 ====================

    @Override
    public Tenant createTenant(Tenant tenant) {
        tenant.setCreatedAt(LocalDateTime.now());
        tenant.setUpdatedAt(LocalDateTime.now());
        tenant.setIsActive(true);

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO ai_tenant (name, code, rpm_limit, tpm_limit, max_concurrent_requests, metadata, is_active, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                    """,
                    new String[]{"id"}  // Specify the generated key column
            );
            ps.setString(1, tenant.getName());
            ps.setString(2, tenant.getCode());
            ps.setLong(3, tenant.getRpmLimit() != null ? tenant.getRpmLimit() : 0);
            ps.setLong(4, tenant.getTpmLimit() != null ? tenant.getTpmLimit() : 0);
            ps.setLong(5, tenant.getMaxConcurrentRequests() != null ? tenant.getMaxConcurrentRequests() : 10L);
            ps.setString(6, tenant.getMetadata() != null ? toJson(tenant.getMetadata()) : "{}");
            ps.setBoolean(7, tenant.getIsActive());
            ps.setObject(8, tenant.getCreatedAt());
            ps.setObject(9, tenant.getUpdatedAt());
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        tenant.setId(key.longValue());

        log.info("Created tenant: id={}, code={}", tenant.getId(), tenant.getCode());

        // Audit logging
        if (auditService != null) {
            auditService.logEvent(AuditEventType.TENANT_CREATED, tenant.getId(), null,
                    "platform_admin", null, Map.of(
                            "code", tenant.getCode(),
                            "rpmLimit", tenant.getRpmLimit() != null ? tenant.getRpmLimit() : 0,
                            "tpmLimit", tenant.getTpmLimit() != null ? tenant.getTpmLimit() : 0
                    ));
        }

        return tenant;
    }

    @Override
    public Tenant getTenant(Long tenantId) {
        List<Tenant> results = jdbcTemplate.query(
                "SELECT * FROM ai_tenant WHERE id = ?",
                tenantRowMapper(),
                tenantId
        );
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public Tenant getTenantByCode(String code) {
        List<Tenant> results = jdbcTemplate.query(
                "SELECT * FROM ai_tenant WHERE code = ?",
                tenantRowMapper(),
                code
        );
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public Tenant updateTenant(Long tenantId, Tenant tenant) {
        tenant.setUpdatedAt(LocalDateTime.now());
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    UPDATE ai_tenant
                    SET name = ?, rpm_limit = ?, tpm_limit = ?, max_concurrent_requests = ?, metadata = ?::jsonb, is_active = ?, updated_at = ?
                    WHERE id = ?
                    """
            );
            ps.setString(1, tenant.getName());
            ps.setLong(2, tenant.getRpmLimit() != null ? tenant.getRpmLimit() : 0);
            ps.setLong(3, tenant.getTpmLimit() != null ? tenant.getTpmLimit() : 0);
            ps.setLong(4, tenant.getMaxConcurrentRequests() != null ? tenant.getMaxConcurrentRequests() : 10);
            ps.setString(5, tenant.getMetadata() != null ? toJson(tenant.getMetadata()) : "{}");
            ps.setBoolean(6, tenant.getIsActive());
            ps.setObject(7, tenant.getUpdatedAt());
            ps.setLong(8, tenantId);
            return ps;
        });
        return getTenant(tenantId);
    }

    @Override
    public void deactivateTenant(Long tenantId) {
        jdbcTemplate.update("UPDATE ai_tenant SET is_active = FALSE, updated_at = ? WHERE id = ?",
                LocalDateTime.now(), tenantId);
        // 停用该租户的所有 Key
        jdbcTemplate.update("UPDATE ai_tenant_api_key SET is_active = FALSE WHERE tenant_id = ?", tenantId);
        log.info("Deactivated tenant: id={}", tenantId);
    }

    @Override
    public List<Tenant> getAllTenants() {
        return jdbcTemplate.query("SELECT * FROM ai_tenant ORDER BY created_at DESC", tenantRowMapper());
    }

    // ==================== API Key 管理 ====================

    @Override
    public ApiKeyGenerateResult generateApiKey(Long tenantId, String userId,
                                                Long rpmLimit, Long tpmLimit,
                                                String allowedTools,
                                                LocalDateTime expiresAt) {
        // 1. 生成随机 Key
        String rawKey = generateSecureRandomKey();
        String keyHash = hashSha256(rawKey);

        // 2. 提取 tenant code 用于显示别名
        Tenant tenant = getTenant(tenantId);
        if (tenant == null) {
            throw new IllegalArgumentException("Tenant not found: " + tenantId);
        }
        String keyAlias = "sk-" + tenant.getCode() + "-xxxx" + rawKey.substring(rawKey.length() - 4);

        // 3. 持久化到数据库
        LocalDateTime now = LocalDateTime.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO ai_tenant_api_key
                    (tenant_id, key_hash, key_alias, user_id, rpm_limit, tpm_limit, allowed_tools, is_active, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?)
                    """,
                    new String[]{"id"}
            );
            ps.setLong(1, tenantId);
            ps.setString(2, keyHash);
            ps.setString(3, keyAlias);
            ps.setString(4, userId);
            ps.setLong(5, rpmLimit != null ? rpmLimit : 0);
            ps.setLong(6, tpmLimit != null ? tpmLimit : 0);
            ps.setString(7, allowedTools);
            ps.setObject(8, now);
            ps.setObject(9, expiresAt);
            return ps;
        }, keyHolder);

        Number keyId = keyHolder.getKey();
        Long keyIdLong = keyId.longValue();

        // 4. 缓存到 Redis
        cacheApiKeyData(keyHash, tenant, keyIdLong, userId, keyAlias, rpmLimit, tpmLimit, allowedTools, now, expiresAt);

        log.info("Generated API Key for tenant: tenantId={}, keyAlias={}", tenantId, keyAlias);

        // Audit logging
        if (auditService != null) {
            auditService.logEvent(AuditEventType.API_KEY_CREATED, tenantId, keyIdLong,
                    "platform_admin", null, Map.of(
                            "userId", userId != null ? userId : "",
                            "rpmLimit", rpmLimit != null ? rpmLimit : 0,
                            "tpmLimit", tpmLimit != null ? tpmLimit : 0,
                            "allowedTools", allowedTools != null ? allowedTools : ""
                    ));
        }

        return new ApiKeyGenerateResult(
                keyIdLong,
                rawKey,              // 仅此时返回原始 Key
                keyAlias,
                tenantId,
                userId,
                rpmLimit,
                tpmLimit,
                allowedTools,
                now,
                expiresAt
        );
    }

    @Override
    public TenantContext validateApiKey(String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isEmpty()) {
            return null;
        }

        String keyHash = hashSha256(rawApiKey);

        // 1. 先查 Redis 缓存
        TenantContext cached = getCachedContext(keyHash);
        if (cached != null) {
            // 检查过期
            if (cached.getExpiresAt() != null && cached.getExpiresAt().isBefore(LocalDateTime.now())) {
                log.warn("API Key expired: hash={}", keyHash.substring(0, 8));
                return null;
            }
            return cached;
        }

        // 2. 缓存未命中，查数据库
        List<TenantContext> contexts = jdbcTemplate.query(
                """
                SELECT ak.*, t.code as tenant_code, t.rpm_limit as tenant_rpm_limit, t.tpm_limit as tenant_tpm_limit
                FROM ai_tenant_api_key ak
                JOIN ai_tenant t ON ak.tenant_id = t.id
                WHERE ak.key_hash = ? AND ak.is_active = TRUE AND t.is_active = TRUE
                """,
                (rs, rowNum) -> {
                    TenantApiKey key = TenantApiKey.builder()
                            .id(rs.getLong("id"))
                            .tenantId(rs.getLong("tenant_id"))
                            .keyHash(rs.getString("key_hash"))
                            .keyAlias(rs.getString("key_alias"))
                            .userId(rs.getString("user_id"))
                            .rpmLimit(rs.getLong("rpm_limit"))
                            .tpmLimit(rs.getLong("tpm_limit"))
                            .allowedTools(rs.getString("allowed_tools"))
                            .isActive(rs.getBoolean("is_active"))
                            .createdAt(rs.getTimestamp("created_at") != null ?
                                    rs.getTimestamp("created_at").toLocalDateTime() : null)
                            .expiresAt(rs.getTimestamp("expires_at") != null ?
                                    rs.getTimestamp("expires_at").toLocalDateTime() : null)
                            .build();

                    TenantContext ctx = TenantContext.builder()
                            .tenantId(key.getTenantId())
                            .tenantCode(rs.getString("tenant_code"))
                            .apiKeyId(key.getId())
                            .userId(key.getUserId())
                            .apiKeyAlias(key.getKeyAlias())
                            .rpmLimit(key.getRpmLimit())
                            .tpmLimit(key.getTpmLimit())
                            .tenantRpmLimit(rs.getLong("tenant_rpm_limit"))
                            .tenantTpmLimit(rs.getLong("tenant_tpm_limit"))
                            .expiresAt(key.getExpiresAt())
                            .build();

                    // 解析 allowedTools
                    if (key.getAllowedTools() != null && !key.getAllowedTools().isEmpty()) {
                        ctx.setAllowedTools(Arrays.asList(key.getAllowedTools().split(",")));
                    } else {
                        ctx.setAllowedTools(Collections.emptyList());
                    }

                    return ctx;
                },
                keyHash
        );

        if (contexts.isEmpty()) {
            log.warn("API Key not found: hash={}", keyHash.substring(0, 8));
            return null;
        }

        TenantContext context = contexts.get(0);

        // 3. 缓存到 Redis
        Tenant tenant = getTenant(context.getTenantId());
        if (tenant != null) {
            context.setTenantCode(tenant.getCode());
            context.setTenantRpmLimit(tenant.getRpmLimit());
            context.setTenantTpmLimit(tenant.getTpmLimit());
        }
        cacheApiKeyContext(keyHash, context);

        // 检查过期
        if (context.getExpiresAt() != null && context.getExpiresAt().isBefore(LocalDateTime.now())) {
            log.warn("API Key expired: hash={}", keyHash.substring(0, 8));
            return null;
        }

        return context;
    }

    @Override
    public void deactivateApiKey(Long keyId) {
        jdbcTemplate.update("UPDATE ai_tenant_api_key SET is_active = FALSE WHERE id = ?", keyId);

        // 从 Redis 删除缓存
        List<String> hashes = jdbcTemplate.queryForList(
                "SELECT key_hash FROM ai_tenant_api_key WHERE id = ?", String.class, keyId
        );
        if (!hashes.isEmpty()) {
            redisTemplate.delete(API_KEY_CACHE_PREFIX + hashes.get(0));
        }

        log.info("Deactivated API Key: id={}", keyId);
    }

    @Override
    public List<TenantApiKey> getKeysByTenant(Long tenantId) {
        return jdbcTemplate.query(
                "SELECT * FROM ai_tenant_api_key WHERE tenant_id = ? ORDER BY created_at DESC",
                (rs, rowNum) -> TenantApiKey.builder()
                        .id(rs.getLong("id"))
                        .tenantId(rs.getLong("tenant_id"))
                        .keyHash(rs.getString("key_hash"))
                        .keyAlias(rs.getString("key_alias"))
                        .userId(rs.getString("user_id"))
                        .rpmLimit(rs.getLong("rpm_limit"))
                        .tpmLimit(rs.getLong("tpm_limit"))
                        .allowedTools(rs.getString("allowed_tools"))
                        .isActive(rs.getBoolean("is_active"))
                        .createdAt(rs.getTimestamp("created_at") != null ?
                                rs.getTimestamp("created_at").toLocalDateTime() : null)
                        .expiresAt(rs.getTimestamp("expires_at") != null ?
                                rs.getTimestamp("expires_at").toLocalDateTime() : null)
                        .build(),
                tenantId
        );
    }

    @Override
    public TenantApiKey getKeyById(Long keyId) {
        List<TenantApiKey> keys = jdbcTemplate.query(
                "SELECT * FROM ai_tenant_api_key WHERE id = ?",
                (rs, rowNum) -> TenantApiKey.builder()
                        .id(rs.getLong("id"))
                        .tenantId(rs.getLong("tenant_id"))
                        .keyHash(rs.getString("key_hash"))
                        .keyAlias(rs.getString("key_alias"))
                        .userId(rs.getString("user_id"))
                        .rpmLimit(rs.getLong("rpm_limit"))
                        .tpmLimit(rs.getLong("tpm_limit"))
                        .allowedTools(rs.getString("allowed_tools"))
                        .isActive(rs.getBoolean("is_active"))
                        .createdAt(rs.getTimestamp("created_at") != null ?
                                rs.getTimestamp("created_at").toLocalDateTime() : null)
                        .expiresAt(rs.getTimestamp("expires_at") != null ?
                                rs.getTimestamp("expires_at").toLocalDateTime() : null)
                        .build(),
                keyId
        );
        return keys.isEmpty() ? null : keys.get(0);
    }

    @Override
    public ApiKeyRotateResult rotateApiKey(Long keyId, int gracePeriodHours) {
        // 1. 获取旧 Key 信息
        TenantApiKey oldKey = getKeyById(keyId);
        if (oldKey == null) {
            throw new IllegalArgumentException("API Key not found: " + keyId);
        }

        // 2. 生成新 Key
        String newRawKey = generateSecureRandomKey();
        String newKeyHash = hashSha256(newRawKey);
        Tenant tenant = getTenant(oldKey.getTenantId());
        if (tenant == null) {
            throw new IllegalArgumentException("Tenant not found: " + oldKey.getTenantId());
        }
        String newKeyAlias = "sk-" + tenant.getCode() + "-xxxx" + newRawKey.substring(newRawKey.length() - 4);

        // 3. 设置旧 Key 过期时间为 gracePeriodHours 后
        LocalDateTime oldKeyExpiresAt = LocalDateTime.now().plusHours(gracePeriodHours);
        jdbcTemplate.update(
                "UPDATE ai_tenant_api_key SET expires_at = ? WHERE id = ?",
                oldKeyExpiresAt, keyId
        );

        // 4. 创建新 Key (复用旧 Key 的 rpm_limit, tpm_limit, allowed_tools, user_id)
        LocalDateTime now = LocalDateTime.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO ai_tenant_api_key
                    (tenant_id, key_hash, key_alias, user_id, rpm_limit, tpm_limit, allowed_tools, is_active, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, TRUE, ?, NULL)
                    """,
                    new String[]{"id"}
            );
            ps.setLong(1, oldKey.getTenantId());
            ps.setString(2, newKeyHash);
            ps.setString(3, newKeyAlias);
            ps.setString(4, oldKey.getUserId());
            ps.setLong(5, oldKey.getRpmLimit());
            ps.setLong(6, oldKey.getTpmLimit());
            ps.setString(7, oldKey.getAllowedTools());
            ps.setObject(8, now);
            return ps;
        }, keyHolder);

        Number newKeyId = keyHolder.getKey();

        log.info("Rotated API Key: oldKeyId={}, newKeyId={}, gracePeriodHours={}",
                keyId, newKeyId, gracePeriodHours);

        // Audit logging
        if (auditService != null) {
            auditService.logEvent(AuditEventType.API_KEY_ROTATED, oldKey.getTenantId(), newKeyId.longValue(),
                    "platform_admin", null, Map.of(
                            "oldKeyId", keyId,
                            "newKeyId", newKeyId.longValue(),
                            "gracePeriodHours", gracePeriodHours
                    ));
        }

        return new ApiKeyRotateResult(
                newKeyId.longValue(),
                newRawKey,
                newKeyAlias,
                oldKeyExpiresAt
        );
    }

    @Override
    public void updateApiKey(Long keyId, Long rpmLimit, Long tpmLimit, String allowedTools) {
        StringBuilder sql = new StringBuilder("UPDATE ai_tenant_api_key SET ");
        java.util.List<Object> params = new java.util.ArrayList<>();

        if (rpmLimit != null) {
            sql.append("rpm_limit = ?, ");
            params.add(rpmLimit);
        }
        if (tpmLimit != null) {
            sql.append("tpm_limit = ?, ");
            params.add(tpmLimit);
        }
        if (allowedTools != null) {
            sql.append("allowed_tools = ?, ");
            params.add(allowedTools);
        }

        if (params.isEmpty()) {
            return; // 无需更新
        }

        sql.append(" WHERE id = ?");
        params.add(keyId);

        jdbcTemplate.update(sql.toString(), params.toArray());
        log.info("Updated API Key: keyId={}", keyId);
    }

    @Override
    public boolean validatePlatformKey(String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isEmpty()) {
            return false;
        }
        String keyHash = hashSha256(rawApiKey);
        List<Integer> count = jdbcTemplate.queryForList(
                "SELECT COUNT(*) FROM ai_platform_api_key WHERE key_hash = ? AND is_active = TRUE AND (expires_at IS NULL OR expires_at > NOW())",
                Integer.class,
                keyHash
        );
        return count.get(0) > 0;
    }

    // ==================== 私有方法 ====================

    private String generateSecureRandomKey() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(bytes)
                .substring(0, 32)
                .toLowerCase()
                .replaceAll("[+/=]", "");
    }

    private String hashSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private void cacheApiKeyContext(String keyHash, TenantContext context) {
        try {
            String cacheKey = API_KEY_CACHE_PREFIX + keyHash;
            String json = objectMapper.writeValueAsString(context);
            redisTemplate.opsForValue().set(cacheKey, json, CACHE_TTL);
        } catch (JsonProcessingException e) {
            log.error("Failed to cache API key context: {}", e.getMessage());
        }
    }

    private void cacheApiKeyData(String keyHash, Tenant tenant, Long keyId, String userId,
                                  String keyAlias, Long rpmLimit, Long tpmLimit,
                                  String allowedTools, LocalDateTime createdAt,
                                  LocalDateTime expiresAt) {
        try {
            String cacheKey = API_KEY_CACHE_PREFIX + keyHash;

            Map<String, Object> data = new HashMap<>();
            data.put("keyId", keyId);
            data.put("tenantId", tenant.getId());
            data.put("tenantCode", tenant.getCode());
            data.put("userId", userId);
            data.put("keyAlias", keyAlias);
            data.put("rpmLimit", rpmLimit);
            data.put("tpmLimit", tpmLimit);
            data.put("allowedTools", allowedTools);
            data.put("isActive", true);
            data.put("createdAt", createdAt.toString());
            data.put("expiresAt", expiresAt != null ? expiresAt.toString() : null);
            data.put("tenantRpmLimit", tenant.getRpmLimit());
            data.put("tenantTpmLimit", tenant.getTpmLimit());

            String json = objectMapper.writeValueAsString(data);
            redisTemplate.opsForValue().set(cacheKey, json, CACHE_TTL);
        } catch (JsonProcessingException e) {
            log.error("Failed to cache API key data: {}", e.getMessage());
        }
    }

    private TenantContext getCachedContext(String keyHash) {
        try {
            String cacheKey = API_KEY_CACHE_PREFIX + keyHash;
            String json = redisTemplate.opsForValue().get(cacheKey);
            if (json != null) {
                return objectMapper.readValue(json, TenantContext.class);
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to parse cached context: {}", e.getMessage());
        }
        return null;
    }

    private RowMapper<Tenant> tenantRowMapper() {
        return (rs, rowNum) -> Tenant.builder()
                .id(rs.getLong("id"))
                .name(rs.getString("name"))
                .code(rs.getString("code"))
                .rpmLimit(rs.getLong("rpm_limit"))
                .tpmLimit(rs.getLong("tpm_limit"))
                .maxConcurrentRequests(rs.getLong("max_concurrent_requests"))
                .metadata(parseJson(rs.getString("metadata")))
                .isActive(rs.getBoolean("is_active"))
                .createdAt(rs.getTimestamp("created_at") != null ?
                        rs.getTimestamp("created_at").toLocalDateTime() : null)
                .updatedAt(rs.getTimestamp("updated_at") != null ?
                        rs.getTimestamp("updated_at").toLocalDateTime() : null)
                .build();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseJson(String json) {
        if (json == null || json.isEmpty()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            return new HashMap<>();
        }
    }
}
