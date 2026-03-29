package com.aiagent.service.knowledge;

import com.aiagent.service.vector.VectorStoreFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 知识库版本管理服务
 * 支持版本创建、激活、灰度、回滚
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseVersionService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final VectorStoreFactory vectorStoreFactory;

    private static final String VERSION_PREFIX = "kb:version:";
    private static final String ACTIVE_VERSION_PREFIX = "kb:active:";
    private static final String COLLECTION_VERSIONS_PREFIX = "kb:collections:";

    // 版本号计数器
    private final Map<String, AtomicInteger> versionCounters = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("KnowledgeBaseVersionService initialized");
    }

    /**
     * 创建新版本
     */
    public KnowledgeBaseVersion createVersion(String collection, String description) {
        int version = getNextVersion(collection);

        KnowledgeBaseVersion versionInfo = KnowledgeBaseVersion.builder()
                .id(collection + "-v" + version)
                .collection(collection)
                .version(version)
                .documentCount(0)
                .status("CREATING")
                .createdAt(Instant.now())
                .description(description)
                .build();

        // 保存版本信息
        String key = VERSION_PREFIX + collection + ":" + version;
        try {
            String value = objectMapper.writeValueAsString(versionInfo);
            redisTemplate.opsForValue().set(key, value, 30, TimeUnit.DAYS);

            // 添加到 collection 版本列表
            redisTemplate.opsForSet().add(COLLECTION_VERSIONS_PREFIX + collection, String.valueOf(version));

            log.info("Created new version {} for collection {}", version, collection);
            return versionInfo;
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize version info", e);
            throw new RuntimeException("Failed to create version", e);
        }
    }

    /**
     * 激活版本
     */
    public void activateVersion(String collection, int version) {
        String versionKey = VERSION_PREFIX + collection + ":" + version;
        String versionJson = redisTemplate.opsForValue().get(versionKey);

        if (versionJson == null) {
            throw new IllegalArgumentException("Version not found: " + version);
        }

        try {
            KnowledgeBaseVersion versionInfo = objectMapper.readValue(versionJson, KnowledgeBaseVersion.class);

            // 检查版本状态
            if ("DELETING".equals(versionInfo.getStatus())) {
                throw new IllegalStateException("Cannot activate version in DELETING status");
            }

            // 更新版本状态
            versionInfo.setStatus("ACTIVE");
            versionInfo.setActivatedAt(Instant.now());

            String updatedJson = objectMapper.writeValueAsString(versionInfo);
            redisTemplate.opsForValue().set(versionKey, updatedJson, 30, TimeUnit.DAYS);

            // 设置活跃版本别名
            String activeKey = ACTIVE_VERSION_PREFIX + collection;
            redisTemplate.opsForValue().set(activeKey, String.valueOf(version), 30, TimeUnit.DAYS);

            // 将旧活跃版本标记为 DEPRECATED
            String oldActiveVersion = redisTemplate.opsForValue().get(activeKey);
            if (oldActiveVersion != null && !oldActiveVersion.equals(String.valueOf(version))) {
                deprecateVersion(collection, Integer.parseInt(oldActiveVersion));
            }

            log.info("Activated version {} for collection {}", version, collection);

        } catch (JsonProcessingException e) {
            log.error("Failed to process version activation", e);
            throw new RuntimeException("Failed to activate version", e);
        }
    }

    /**
     * 弃用版本
     */
    public void deprecateVersion(String collection, int version) {
        String versionKey = VERSION_PREFIX + collection + ":" + version;
        String versionJson = redisTemplate.opsForValue().get(versionKey);

        if (versionJson == null) {
            return;
        }

        try {
            KnowledgeBaseVersion versionInfo = objectMapper.readValue(versionJson, KnowledgeBaseVersion.class);
            versionInfo.setStatus("DEPRECATED");

            String updatedJson = objectMapper.writeValueAsString(versionInfo);
            redisTemplate.opsForValue().set(versionKey, updatedJson, 30, TimeUnit.DAYS);

            log.info("Deprecated version {} for collection {}", version, collection);
        } catch (JsonProcessingException e) {
            log.error("Failed to deprecate version", e);
        }
    }

    /**
     * 回滚到指定版本
     */
    public void rollback(String collection, int targetVersion) {
        // 检查目标版本是否存在
        String versionKey = VERSION_PREFIX + collection + ":" + targetVersion;
        if (redisTemplate.opsForValue().get(versionKey) == null) {
            throw new IllegalArgumentException("Target version not found: " + targetVersion);
        }

        // 激活目标版本
        activateVersion(collection, targetVersion);
        log.info("Rolled back collection {} to version {}", collection, targetVersion);
    }

    /**
     * 获取活跃版本
     */
    public Optional<Integer> getActiveVersion(String collection) {
        String activeKey = ACTIVE_VERSION_PREFIX + collection;
        String version = redisTemplate.opsForValue().get(activeKey);

        if (version == null) {
            return Optional.empty();
        }

        return Optional.of(Integer.parseInt(version));
    }

    /**
     * 获取版本信息
     */
    public Optional<KnowledgeBaseVersion> getVersionInfo(String collection, int version) {
        String versionKey = VERSION_PREFIX + collection + ":" + version;
        String versionJson = redisTemplate.opsForValue().get(versionKey);

        if (versionJson == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(objectMapper.readValue(versionJson, KnowledgeBaseVersion.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize version info", e);
            return Optional.empty();
        }
    }

    /**
     * 获取 collection 所有版本
     */
    public List<KnowledgeBaseVersion> getAllVersions(String collection) {
        Set<String> versions = redisTemplate.opsForSet().members(COLLECTION_VERSIONS_PREFIX + collection);
        if (versions == null || versions.isEmpty()) {
            return Collections.emptyList();
        }

        List<KnowledgeBaseVersion> result = new ArrayList<>();
        for (String v : versions) {
            getVersionInfo(collection, Integer.parseInt(v)).ifPresent(result::add);
        }

        // 按版本号排序
        result.sort(Comparator.comparingInt(KnowledgeBaseVersion::getVersion).reversed());
        return result;
    }

    /**
     * 标记版本删除
     */
    public void markForDeletion(String collection, int version) {
        String versionKey = VERSION_PREFIX + collection + ":" + version;
        String versionJson = redisTemplate.opsForValue().get(versionKey);

        if (versionJson == null) {
            return;
        }

        try {
            KnowledgeBaseVersion versionInfo = objectMapper.readValue(versionJson, KnowledgeBaseVersion.class);
            versionInfo.setStatus("DELETING");

            String updatedJson = objectMapper.writeValueAsString(versionInfo);
            redisTemplate.opsForValue().set(versionKey, updatedJson, 30, TimeUnit.DAYS);

            log.info("Marked version {} for deletion", collection + ":" + version);
        } catch (JsonProcessingException e) {
            log.error("Failed to mark version for deletion", e);
        }
    }

    /**
     * 获取下一个版本号
     */
    private int getNextVersion(String collection) {
        AtomicInteger counter = versionCounters.computeIfAbsent(collection,
                k -> new AtomicInteger(0));
        return counter.incrementAndGet();
    }

    /**
     * 获取版本统计
     */
    public Map<String, Object> getVersionStats(String collection) {
        List<KnowledgeBaseVersion> versions = getAllVersions(collection);

        Map<String, Object> stats = new HashMap<>();
        stats.put("collection", collection);
        stats.put("totalVersions", versions.size());

        long activeCount = versions.stream()
                .filter(v -> "ACTIVE".equals(v.getStatus()))
                .count();
        stats.put("activeVersions", activeCount);

        long totalDocs = versions.stream()
                .filter(v -> "ACTIVE".equals(v.getStatus()))
                .mapToLong(KnowledgeBaseVersion::getDocumentCount)
                .sum();
        stats.put("totalDocuments", totalDocs);

        getActiveVersion(collection).ifPresent(v -> stats.put("currentVersion", v));

        return stats;
    }
}
