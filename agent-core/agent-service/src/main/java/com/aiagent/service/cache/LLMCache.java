package com.aiagent.service.cache;

import com.aiagent.common.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LLM 响应缓存
 * 使用 Redis 存储，key 为 prompt 的 hash
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LLMCache {

    private final StringRedisTemplate redisTemplate;

    @Value("${aiagent.cache.llm.enabled:true}")
    private boolean enabled;

    @Value("${aiagent.cache.llm.ttl:3600}")
    private long ttlSeconds;

    @Value("${aiagent.cache.llm.max-size:10000}")
    private int maxCacheSize;

    /**
     * 本地缓存统计
     */
    private final ConcurrentHashMap<String, CacheStats> localStats = new ConcurrentHashMap<>();

    /**
     * 缓存命中统计
     */
    private static class CacheStats {
        public final AtomicLong hits = new AtomicLong(0);
        public final AtomicLong misses = new AtomicLong(0);
    }

    /**
     * 生成缓存 key
     */
    private String generateCacheKey(String prompt, String provider, String model) {
        try {
            String rawKey = prompt + ":" + provider + ":" + model;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return Constants.RedisKey.LLM_CACHE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 not available, using fallback", e);
            return Constants.RedisKey.LLM_CACHE_PREFIX + prompt.hashCode();
        }
    }

    /**
     * 获取缓存内容
     *
     * @param prompt   提示词
     * @param provider LLM provider
     * @param model    模型名称
     * @return 缓存的响应内容，如果未命中则返回 null
     */
    public String get(String prompt, String provider, String model) {
        if (!enabled) {
            return null;
        }

        try {
            String cacheKey = generateCacheKey(prompt, provider, model);
            String cached = redisTemplate.opsForValue().get(cacheKey);

            if (cached != null) {
                log.debug("LLM cache hit for key: {}", cacheKey.substring(cacheKey.lastIndexOf(':') + 1));
                recordHit(prompt);
                return cached;
            }

            recordMiss(prompt);
            return null;
        } catch (Exception e) {
            log.error("LLM cache get error", e);
            return null;
        }
    }

    /**
     * 存储 LLM 响应到缓存
     *
     * @param prompt      提示词
     * @param provider    LLM provider
     * @param model       模型名称
     * @param response    LLM 响应内容
     */
    public void put(String prompt, String provider, String model, String response) {
        if (!enabled || response == null || response.isEmpty()) {
            return;
        }

        try {
            String cacheKey = generateCacheKey(prompt, provider, model);
            redisTemplate.opsForValue().set(cacheKey, response, Duration.ofSeconds(ttlSeconds));
            log.debug("LLM response cached with key: {}", cacheKey.substring(cacheKey.lastIndexOf(':') + 1));
        } catch (Exception e) {
            log.error("LLM cache put error", e);
        }
    }

    /**
     * 检查缓存是否命中
     */
    public boolean contains(String prompt, String provider, String model) {
        return get(prompt, provider, model) != null;
    }

    /**
     * 使缓存失效
     */
    public void invalidate(String prompt, String provider, String model) {
        try {
            String cacheKey = generateCacheKey(prompt, provider, model);
            redisTemplate.delete(cacheKey);
            log.debug("LLM cache invalidated for key: {}", cacheKey);
        } catch (Exception e) {
            log.error("LLM cache invalidate error", e);
        }
    }

    /**
     * 清空所有 LLM 缓存
     */
    public void clear() {
        try {
            var keys = redisTemplate.keys(Constants.RedisKey.LLM_CACHE_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("Cleared {} LLM cache entries", keys.size());
            }
        } catch (Exception e) {
            log.error("LLM cache clear error", e);
        }
    }

    /**
     * 获取缓存命中率
     */
    public double getHitRate() {
        CacheStats stats = localStats.values().stream()
                .findAny()
                .orElse(new CacheStats());

        long hits = stats.hits.get();
        long misses = stats.misses.get();
        long total = hits + misses;

        return total > 0 ? (double) hits / total : 0.0;
    }

    /**
     * 获取缓存统计信息
     */
    public CacheInfo getCacheInfo() {
        long totalHits = 0;
        long totalMisses = 0;

        for (CacheStats stats : localStats.values()) {
            totalHits += stats.hits.get();
            totalMisses += stats.misses.get();
        }

        return new CacheInfo(totalHits, totalMisses, enabled);
    }

    private void recordHit(String prompt) {
        String key = prompt.length() > 50 ? prompt.substring(0, 50) : prompt;
        localStats.computeIfAbsent(key, k -> new CacheStats()).hits.incrementAndGet();
    }

    private void recordMiss(String prompt) {
        String key = prompt.length() > 50 ? prompt.substring(0, 50) : prompt;
        localStats.computeIfAbsent(key, k -> new CacheStats()).misses.incrementAndGet();
    }

    /**
     * 缓存统计信息
     */
    public record CacheInfo(long hits, long misses, boolean enabled) {
        public long total() {
            return hits + misses;
        }

        public double hitRate() {
            return total() > 0 ? (double) hits / total() : 0.0;
        }
    }
}
