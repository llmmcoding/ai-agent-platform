package com.aiagent.service.cache;

import com.aiagent.service.metrics.AgentMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * RAG 缓存服务
 * 缓存 RAG 查询结果，加速热门 query 响应
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGCacheService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentMetrics agentMetrics;

    @Value("${aiagent.rag-cache.enabled:true}")
    private boolean cacheEnabled;

    @Value("${aiagent.rag-cache.ttl-seconds:300}")
    private int cacheTtlSeconds;

    @Value("${aiagent.rag-cache.max-entries:10000}")
    private int maxEntries;

    private static final String CACHE_PREFIX = "rag:cache:";

    /**
     * 获取缓存
     */
    @SuppressWarnings("unchecked")
    public Optional<List<Map<String, Object>>> get(String query, String collection, Map<String, String> filters) {
        if (!cacheEnabled) {
            return Optional.empty();
        }

        String cacheKey = generateCacheKey(query, collection, filters);

        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                List<Map<String, Object>> results = objectMapper.readValue(cached, List.class);

                if (agentMetrics != null) {
                    agentMetrics.incrementRAGCacheHit();
                }

                log.debug("RAG cache hit for key: {}", cacheKey);
                return Optional.of(results);
            }
        } catch (Exception e) {
            log.warn("Failed to get RAG cache: {}", e.getMessage());
        }

        if (agentMetrics != null) {
            agentMetrics.incrementRAGCacheMiss();
        }

        log.debug("RAG cache miss for key: {}", cacheKey);
        return Optional.empty();
    }

    /**
     * 设置缓存
     */
    public void put(String query, String collection, Map<String, String> filters,
                   List<Map<String, Object>> results) {
        if (!cacheEnabled || results == null || results.isEmpty()) {
            return;
        }

        String cacheKey = generateCacheKey(query, collection, filters);

        try {
            String value = objectMapper.writeValueAsString(results);
            redisTemplate.opsForValue().set(cacheKey, value, cacheTtlSeconds, TimeUnit.SECONDS);

            // 更新缓存统计
            updateCacheStats(collection, true);

            log.debug("Cached RAG results for key: {}, TTL: {}s", cacheKey, cacheTtlSeconds);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize RAG results for cache: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to set RAG cache: {}", e.getMessage());
        }
    }

    /**
     * 使缓存失效
     */
    public void invalidate(String query, String collection, Map<String, String> filters) {
        String cacheKey = generateCacheKey(query, collection, filters);
        try {
            redisTemplate.delete(cacheKey);
            log.debug("Invalidated RAG cache for key: {}", cacheKey);
        } catch (Exception e) {
            log.warn("Failed to invalidate RAG cache: {}", e.getMessage());
        }
    }

    /**
     * 使用 SCAN 安全地扫描匹配的 keys（避免 KEYS 阻塞 Redis）
     */
    private Set<String> scanKeys(String pattern) {
        return redisTemplate.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> keys = new HashSet<>();
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            }
            return keys;
        });
    }

    /**
     * 使整个 collection 的缓存失效
     */
    public void invalidateCollection(String collection) {
        try {
            Set<String> keys = scanKeys(CACHE_PREFIX + "*" + collection + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("Invalidated {} RAG cache entries for collection: {}", keys.size(), collection);
            }
        } catch (Exception e) {
            log.warn("Failed to invalidate RAG cache for collection: {}", e.getMessage());
        }
    }

    /**
     * 清空所有 RAG 缓存
     */
    public void clear() {
        try {
            Set<String> keys = scanKeys(CACHE_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("Cleared {} RAG cache entries", keys.size());
            }
        } catch (Exception e) {
            log.warn("Failed to clear RAG cache: {}", e.getMessage());
        }
    }

    /**
     * 获取缓存统计
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("enabled", cacheEnabled);
        stats.put("ttlSeconds", cacheTtlSeconds);
        stats.put("maxEntries", maxEntries);

        try {
            Set<String> keys = scanKeys(CACHE_PREFIX + "*");
            stats.put("currentEntries", keys != null ? keys.size() : 0);
        } catch (Exception e) {
            stats.put("currentEntries", -1);
            stats.put("error", e.getMessage());
        }

        return stats;
    }

    /**
     * 生成缓存 Key
     */
    private String generateCacheKey(String query, String collection, Map<String, String> filters) {
        // 构建缓存 key: rag:cache:{hash(query+collection+filters)}
        StringBuilder sb = new StringBuilder();
        sb.append(query != null ? query : "");
        sb.append(":");
        sb.append(collection != null ? collection : "");
        sb.append(":");
        if (filters != null && !filters.isEmpty()) {
            // 按 key 排序确保一致性
            List<String> sortedKeys = new ArrayList<>(filters.keySet());
            Collections.sort(sortedKeys);
            for (String key : sortedKeys) {
                sb.append(key).append("=").append(filters.get(key)).append(";");
            }
        }

        String hash = sha256(sb.toString());
        return CACHE_PREFIX + hash;
    }

    /**
     * SHA-256 Hash
     */
    private String sha256(String input) {
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
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * 更新缓存统计
     */
    private void updateCacheStats(String collection, boolean increment) {
        try {
            String statsKey = "rag:cache:stats:" + collection;
            if (increment) {
                redisTemplate.opsForValue().increment(statsKey);
            }
            redisTemplate.expire(statsKey, Duration.ofHours(24));
        } catch (Exception e) {
            // ignore
        }
    }
}
