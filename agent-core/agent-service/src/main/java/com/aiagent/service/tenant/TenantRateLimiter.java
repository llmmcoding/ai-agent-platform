package com.aiagent.service.tenant;

import com.aiagent.common.dto.TenantContext;
import com.aiagent.service.alert.QuotaAlertService;
import com.aiagent.service.usage.ApiUsageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 租户感知限流器
 * 支持 per-Key 和 per-Tenant 两级限流
 */
@Slf4j
@Component
public class TenantRateLimiter {

    private final StringRedisTemplate redisTemplate;
    private final ApiUsageService apiUsageService;
    private final QuotaAlertService quotaAlertService;

    @Value("${aiagent.ratelimit.window-seconds:60}")
    private int windowSeconds;

    // Redis Key 前缀
    private static final String KEY_RPM_PREFIX = "aiagent:ratelimit:key:";
    private static final String KEY_TPM_PREFIX = "aiagent:ratelimit:key:tpm:";
    private static final String TENANT_RPM_PREFIX = "aiagent:ratelimit:tenant:";
    private static final String TENANT_TPM_PREFIX = "aiagent:ratelimit:tenant:tpm:";

    /**
     * Lua 脚本：原子性增加计数并设置过期时间
     * 返回增加后的值
     */
    private static final String INCR_WITH_EXPIRE_SCRIPT = """
        local key = KEYS[1]
        local increment = tonumber(ARGV[1])
        local expire = tonumber(ARGV[2])

        local current = redis.call('INCRBY', key, increment)

        -- 只有首次设置时才设置过期时间
        if current == increment then
            redis.call('EXPIRE', key, expire)
        end

        return current
        """;

    private static final DefaultRedisScript<Long> INCR_WITH_EXPIRE_SCRIPT_INSTANCE;

    static {
        INCR_WITH_EXPIRE_SCRIPT_INSTANCE = new DefaultRedisScript<>();
        INCR_WITH_EXPIRE_SCRIPT_INSTANCE.setScriptText(INCR_WITH_EXPIRE_SCRIPT);
        INCR_WITH_EXPIRE_SCRIPT_INSTANCE.setResultType(Long.class);
    }

    public TenantRateLimiter(StringRedisTemplate redisTemplate,
                            @Autowired(required = false) ApiUsageService apiUsageService,
                            @Autowired(required = false) QuotaAlertService quotaAlertService) {
        this.redisTemplate = redisTemplate;
        this.apiUsageService = apiUsageService;
        this.quotaAlertService = quotaAlertService;
    }

    /**
     * 检查限流
     *
     * @param context 租户上下文
     * @param tokenCount 本次请求的 token 数量
     * @return 限流结果
     */
    public RateLimitResult checkRateLimit(TenantContext context, int tokenCount) {
        if (context == null) {
            // 无上下文，跳过限流检查
            return RateLimitResult.allow();
        }

        String keyId = context.getApiKeyId().toString();
        String tenantId = context.getTenantId().toString();

        // 1. 检查 Key 级别 RPM (使用Lua脚本保证原子性)
        if (context.getRpmLimit() != null && context.getRpmLimit() > 0) {
            String rpmKey = KEY_RPM_PREFIX + keyId;
            Long current = executeIncrWithExpire(rpmKey, 1);
            if (current != null && current > context.getRpmLimit()) {
                log.warn("Key RPM exceeded: keyId={}, limit={}, current={}",
                        keyId, context.getRpmLimit(), current);
                checkAndAlert(context, "RPM", context.getRpmLimit(), current);
                return RateLimitResult.exceeded("RPM", context.getRpmLimit(), keyId);
            }
        }

        // 2. 检查 Key 级别 TPM (使用Lua脚本保证原子性)
        if (context.getTpmLimit() != null && context.getTpmLimit() > 0) {
            String tpmKey = KEY_TPM_PREFIX + keyId;
            Long current = executeIncrWithExpire(tpmKey, tokenCount);
            if (current != null && current > context.getTpmLimit()) {
                log.warn("Key TPM exceeded: keyId={}, limit={}, current={}",
                        keyId, context.getTpmLimit(), current);
                checkAndAlert(context, "TPM", context.getTpmLimit(), current);
                return RateLimitResult.exceeded("TPM", context.getTpmLimit(), keyId);
            }
        }

        // 3. 检查租户级别 RPM (聚合, 使用Lua脚本保证原子性)
        if (context.getTenantRpmLimit() != null && context.getTenantRpmLimit() > 0) {
            String tenantRpmKey = TENANT_RPM_PREFIX + tenantId;
            Long current = executeIncrWithExpire(tenantRpmKey, 1);
            if (current != null && current > context.getTenantRpmLimit()) {
                log.warn("Tenant RPM exceeded: tenantId={}, limit={}, current={}",
                        tenantId, context.getTenantRpmLimit(), current);
                checkAndAlert(context, "TenantRPM", context.getTenantRpmLimit(), current);
                return RateLimitResult.exceeded("Tenant RPM", context.getTenantRpmLimit(), tenantId);
            }
        }

        // 4. 检查租户级别 TPM (聚合, 使用Lua脚本保证原子性)
        if (context.getTenantTpmLimit() != null && context.getTenantTpmLimit() > 0) {
            String tenantTpmKey = TENANT_TPM_PREFIX + tenantId;
            Long current = executeIncrWithExpire(tenantTpmKey, tokenCount);
            if (current != null && current > context.getTenantTpmLimit()) {
                log.warn("Tenant TPM exceeded: tenantId={}, limit={}, current={}",
                        tenantId, context.getTenantTpmLimit(), current);
                checkAndAlert(context, "TenantTPM", context.getTenantTpmLimit(), current);
                return RateLimitResult.exceeded("Tenant TPM", context.getTenantTpmLimit(), tenantId);
            }
        }

        return RateLimitResult.allow();
    }

    /**
     * 执行原子性增加并设置过期时间
     */
    private Long executeIncrWithExpire(String key, int increment) {
        try {
            List<String> keys = Arrays.asList(key);
            return redisTemplate.execute(
                    INCR_WITH_EXPIRE_SCRIPT_INSTANCE,
                    keys,
                    String.valueOf(increment),
                    String.valueOf(windowSeconds)
            );
        } catch (Exception e) {
            log.error("Failed to execute rate limit script for key: {}", key, e);
            return null;
        }
    }

    /**
     * 记录 API 使用量 (请求完成后调用)
     */
    public void recordUsage(Long tenantId, Long apiKeyId, int inputTokens, int outputTokens,
                          long latencyMs, boolean isError) {
        if (apiUsageService != null) {
            apiUsageService.recordUsage(tenantId, apiKeyId, inputTokens, outputTokens, latencyMs, isError);
        }
    }

    /**
     * 检查并触发告警
     */
    private void checkAndAlert(TenantContext context, String limitType, long limit, long current) {
        if (quotaAlertService != null) {
            quotaAlertService.checkAndAlert(context.getTenantId(), context.getApiKeyId(),
                    limitType, limit, current);
        }
    }

    /**
     * 获取当前 Key 的使用量
     */
    public RateLimitUsage getKeyUsage(Long apiKeyId) {
        String keyId = apiKeyId.toString();

        String rpmKey = KEY_RPM_PREFIX + keyId;
        String tpmKey = KEY_TPM_PREFIX + keyId;

        String rpmStr = redisTemplate.opsForValue().get(rpmKey);
        String tpmStr = redisTemplate.opsForValue().get(tpmKey);

        return new RateLimitUsage(
                rpmStr != null ? Long.parseLong(rpmStr) : 0,
                tpmStr != null ? Long.parseLong(tpmStr) : 0
        );
    }

    /**
     * 获取当前租户的使用量
     */
    public RateLimitUsage getTenantUsage(Long tenantId) {
        String tid = tenantId.toString();

        String rpmKey = TENANT_RPM_PREFIX + tid;
        String tpmKey = TENANT_TPM_PREFIX + tid;

        String rpmStr = redisTemplate.opsForValue().get(rpmKey);
        String tpmStr = redisTemplate.opsForValue().get(tpmKey);

        return new RateLimitUsage(
                rpmStr != null ? Long.parseLong(rpmStr) : 0,
                tpmStr != null ? Long.parseLong(tpmStr) : 0
        );
    }

    /**
     * 限流结果
     */
    public record RateLimitResult(
            boolean allowed,
            String limitType,
            Long limit,
            String scope
    ) {
        public static RateLimitResult allow() {
            return new RateLimitResult(true, null, null, null);
        }

        public static RateLimitResult exceeded(String limitType, Long limit, String scope) {
            return new RateLimitResult(false, limitType, limit, scope);
        }
    }

    /**
     * 限流使用量
     */
    public record RateLimitUsage(
            long currentRpm,
            long currentTpm
    ) {}
}
