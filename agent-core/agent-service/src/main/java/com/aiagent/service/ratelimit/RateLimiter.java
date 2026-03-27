package com.aiagent.service.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的多维度限流器
 * 支持 RPM (Requests Per Minute) 和 TPM (Tokens Per Minute) 双维度限流
 * 借鉴 llmgateway 的 Legitimate 限流设计
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimiter {

    private final StringRedisTemplate redisTemplate;

    @Value("${aiagent.ratelimit.enabled:true}")
    private boolean enabled;

    // RPM 限流
    @Value("${aiagent.ratelimit.rpm.enabled:true}")
    private boolean rpmEnabled;

    @Value("${aiagent.ratelimit.rpm.max-requests:100}")
    private int rpmMaxRequests;

    // TPM 限流
    @Value("${aiagent.ratelimit.tpm.enabled:true}")
    private boolean tpmEnabled;

    @Value("${aiagent.ratelimit.tpm.max-tokens:50000}")
    private int tpmMaxTokens;

    // 窗口大小(分钟)
    @Value("${aiagent.ratelimit.window-minutes:1}")
    private int windowMinutes;

    private static final String RPM_PREFIX = "ai:ratelimit:rpm:";
    private static final String TPM_PREFIX = "ai:ratelimit:tpm:";

    /**
     * Lua 脚本：原子性增加计数并检查限制
     */
    private static final String RATE_LIMIT_SCRIPT = """
        local key = KEYS[1]
        local limit = tonumber(ARGV[1])
        local window = tonumber(ARGV[2])
        local now = tonumber(ARGV[3])
        local expire = tonumber(ARGV[4])

        local current = redis.call('GET', key)
        if current == false then
            current = 0
        else
            current = tonumber(current)
        end

        if current >= limit then
            return 0
        end

        redis.call('INCR', key)
        redis.call('EXPIRE', key, expire)

        return 1
        """;

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT_INSTANCE;

    static {
        RATE_LIMIT_SCRIPT_INSTANCE = new DefaultRedisScript<>();
        RATE_LIMIT_SCRIPT_INSTANCE.setScriptText(RATE_LIMIT_SCRIPT);
        RATE_LIMIT_SCRIPT_INSTANCE.setResultType(Long.class);
    }

    /**
     * 检查请求是否允许通过 (RPM)
     *
     * @param key 用户/租户标识
     * @return true 允许, false 限流
     */
    public boolean tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    /**
     * 检查请求是否允许通过 (RPM + TPM)
     *
     * @param key 用户/租户标识
     * @param tokens 本次请求的 token 数量
     * @return true 允许, false 限流
     */
    public boolean tryAcquire(String key, int tokens) {
        if (!enabled) {
            return true;
        }

        try {
            // 1. 检查 RPM
            if (rpmEnabled && !tryAcquireRpm(key)) {
                log.warn("RPM limit exceeded for key: {}", key);
                return false;
            }

            // 2. 检查 TPM
            if (tpmEnabled && tokens > 0 && !tryAcquireTpm(key, tokens)) {
                log.warn("TPM limit exceeded for key: {}, tokens: {}", key, tokens);
                return false;
            }

            return true;
        } catch (Exception e) {
            log.error("Rate limiter error for key: {}, allowing request", key, e);
            return true;
        }
    }

    /**
     * RPM 限流检查
     */
    private boolean tryAcquireRpm(String key) {
        if (!rpmEnabled) {
            return true;
        }

        String redisKey = RPM_PREFIX + key;
        long now = System.currentTimeMillis() / 1000;
        long windowSeconds = windowMinutes * 60L;
        long expire = windowSeconds + 10; // 多加10秒缓冲

        List<String> keys = Arrays.asList(redisKey);
        Long result = redisTemplate.execute(
                RATE_LIMIT_SCRIPT_INSTANCE,
                keys,
                String.valueOf(rpmMaxRequests),
                String.valueOf(windowSeconds),
                String.valueOf(now),
                String.valueOf(expire)
        );

        return result != null && result == 1L;
    }

    /**
     * TPM 限流检查
     */
    private boolean tryAcquireTpm(String key, int tokens) {
        if (!tpmEnabled) {
            return true;
        }

        String redisKey = TPM_PREFIX + key;
        long now = System.currentTimeMillis() / 1000;
        long windowSeconds = windowMinutes * 60L;
        long expire = windowSeconds + 10;

        // 滑动窗口逻辑
        String currentKey = redisKey + ":current";
        String historyKey = redisKey + ":history";

        // 获取当前窗口已使用的 tokens
        String currentTokensStr = redisTemplate.opsForValue().get(currentKey);
        int currentTokens = currentTokensStr != null ? Integer.parseInt(currentTokensStr) : 0;

        // 检查是否会超出限制
        if (currentTokens + tokens > tpmMaxTokens) {
            return false;
        }

        // 增加 token 计数
        redisTemplate.opsForValue().increment(currentKey, tokens);
        redisTemplate.expire(currentKey, Duration.ofSeconds(expire));

        return true;
    }

    /**
     * 获取当前 RPM 使用量
     */
    public long getCurrentRpm(String key) {
        try {
            String redisKey = RPM_PREFIX + key;
            String value = redisTemplate.opsForValue().get(redisKey);
            return value != null ? Long.parseLong(value) : 0;
        } catch (Exception e) {
            log.error("Failed to get current RPM for key: {}", key, e);
            return 0;
        }
    }

    /**
     * 获取当前 TPM 使用量
     */
    public long getCurrentTpm(String key) {
        try {
            String redisKey = TPM_PREFIX + key;
            String value = redisTemplate.opsForValue().get(redisKey + ":current");
            return value != null ? Long.parseLong(value) : 0;
        } catch (Exception e) {
            log.error("Failed to get current TPM for key: {}", key, e);
            return 0;
        }
    }

    /**
     * 获取剩余可用配额
     */
    public RateLimitStatus getStatus(String key) {
        long rpmUsed = getCurrentRpm(key);
        long tpmUsed = getCurrentTpm(key);

        return RateLimitStatus.builder()
                .rpmUsed(rpmUsed)
                .rpmLimit(rpmMaxRequests)
                .rpmRemaining(Math.max(0, rpmMaxRequests - rpmUsed))
                .tpmUsed(tpmUsed)
                .tpmLimit(tpmMaxTokens)
                .tpmRemaining(Math.max(0, tpmMaxTokens - tpmUsed))
                .build();
    }

    /**
     * 重置限流计数器
     */
    public void reset(String key) {
        try {
            redisTemplate.delete(RPM_PREFIX + key);
            redisTemplate.delete(TPM_PREFIX + key + ":current");
            redisTemplate.delete(TPM_PREFIX + key + ":history");
            log.info("Rate limit reset for key: {}", key);
        } catch (Exception e) {
            log.error("Failed to reset rate limit for key: {}", key, e);
        }
    }

    /**
     * 限流状态信息
     */
    @lombok.Data
    @lombok.Builder
    public static class RateLimitStatus {
        private long rpmUsed;
        private long rpmLimit;
        private long rpmRemaining;
        private long tpmUsed;
        private long tpmLimit;
        private long tpmRemaining;
    }
}
