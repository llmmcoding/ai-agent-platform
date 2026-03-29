package com.aiagent.service.consistency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 消息幂等性保障服务
 * 确保 Kafka 消息不会重复处理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageIdempotencyService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String PROCESSED_PREFIX = "msg:processed:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    /**
     * 检查消息是否已处理
     *
     * @param messageId 消息 ID
     * @return true if already processed
     */
    public boolean isProcessed(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return false;
        }
        String key = PROCESSED_PREFIX + messageId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * 标记消息已处理
     *
     * @param messageId 消息 ID
     * @param ttl 过期时间
     */
    public void markProcessed(String messageId, Duration ttl) {
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        String key = PROCESSED_PREFIX + messageId;
        redisTemplate.opsForValue().set(key, "1", ttl);
        log.debug("Marked message as processed: {}", messageId);
    }

    /**
     * 标记消息已处理 (默认 24 小时)
     */
    public void markProcessed(String messageId) {
        markProcessed(messageId, DEFAULT_TTL);
    }

    /**
     * 尝试获取消息处理锁 (防止并发处理)
     *
     * @param messageId 消息 ID
     * @param lockTtl 锁过期时间
     * @return true if lock acquired
     */
    public boolean tryAcquireLock(String messageId, Duration lockTtl) {
        if (messageId == null || messageId.isBlank()) {
            return false;
        }
        String lockKey = "msg:lock:" + messageId;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", lockTtl);
        if (Boolean.TRUE.equals(acquired)) {
            log.debug("Acquired lock for message: {}", messageId);
            return true;
        }
        log.debug("Failed to acquire lock for message: {}", messageId);
        return false;
    }

    /**
     * 释放消息处理锁
     */
    public void releaseLock(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        String lockKey = "msg:lock:" + messageId;
        redisTemplate.delete(lockKey);
        log.debug("Released lock for message: {}", messageId);
    }

    /**
     * 检查并获取锁 (原子操作)
     *
     * @param messageId 消息 ID
     * @param lockTtl 锁过期时间
     * @return true if lock acquired (message not processed)
     */
    public boolean checkAndLock(String messageId, Duration lockTtl) {
        // 先检查是否已处理
        if (isProcessed(messageId)) {
            log.debug("Message already processed: {}", messageId);
            return false;
        }

        // 尝试获取锁
        return tryAcquireLock(messageId, lockTtl);
    }

    /**
     * 确认消息处理完成
     */
    public void confirmProcessed(String messageId) {
        markProcessed(messageId);
        releaseLock(messageId);
    }

    /**
     * 获取当前锁定的消息数量
     */
    public long getLockedMessageCount() {
        Set<String> keys = redisTemplate.keys("msg:lock:*");
        return keys != null ? keys.size() : 0;
    }

    /**
     * 清理过期锁 (定时调用)
     */
    public void cleanupExpiredLocks() {
        Set<String> lockKeys = redisTemplate.keys("msg:lock:*");
        if (lockKeys != null && !lockKeys.isEmpty()) {
            long cleaned = 0;
            for (String key : lockKeys) {
                if (!Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
                    redisTemplate.delete(key);
                    cleaned++;
                }
            }
            if (cleaned > 0) {
                log.info("Cleaned up {} expired locks", cleaned);
            }
        }
    }
}
