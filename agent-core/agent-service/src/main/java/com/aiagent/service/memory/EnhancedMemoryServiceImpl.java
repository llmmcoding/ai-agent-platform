package com.aiagent.service.memory;

import com.aiagent.service.embedding.EmbeddingService;
import com.aiagent.service.vector.VectorStoreFactory;
import com.aiagent.service.vector.VectorStoreFactory.VectorSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 增强记忆服务实现
 * 情景记忆: Milvus 向量存储
 * 事实记忆: Milvus 向量存储 (entityKey 唯一)
 * 偏好记忆: Redis Hash (高频访问)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnhancedMemoryServiceImpl implements EnhancedMemoryService {

    private final VectorStoreFactory vectorStoreFactory;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final EmbeddingService embeddingService;

    // Collection 名称
    @Value("${aiagent.memory.collections.episodic:episodic_memory}")
    private String episodicCollection;

    @Value("${aiagent.memory.collections.factual:factual_memory}")
    private String factualCollection;

    // Redis Key 前缀
    private static final String PREFERENCE_KEY_PREFIX = "ai:memory:preference:";
    private static final String STATS_KEY_PREFIX = "ai:memory:stats:";
    private static final String SHORT_TERM_KEY_PREFIX = "ai:memory:session:";

    // 偏好记忆 TTL (30 天)
    private static final long PREFERENCE_TTL_DAYS = 30;

    @Override
    public String getShortTermMemory(String sessionId) {
        try {
            String key = SHORT_TERM_KEY_PREFIX + sessionId;
            Set<String> entriesJson = redisTemplate.opsForZSet().reverseRange(key, 0, -1);

            if (entriesJson == null || entriesJson.isEmpty()) {
                return "";
            }

            StringBuilder context = new StringBuilder();
            for (String entryJson : entriesJson) {
                try {
                    MemoryEntry entry = objectMapper.readValue(entryJson, MemoryEntry.class);
                    context.append(entry.getContent()).append("\n");
                } catch (Exception e) {
                    log.warn("Failed to parse memory entry: {}", e.getMessage());
                }
            }
            return context.toString().trim();
        } catch (Exception e) {
            log.error("Failed to get short term memory: {}", e.getMessage(), e);
            return "";
        }
    }

    @Override
    public void save(MemoryEntry entry) {
        if (entry.getUserId() == null) {
            log.warn("Cannot save memory without userId");
            return;
        }

        // 生成 ID
        if (entry.getId() == null) {
            entry.setId(UUID.randomUUID().toString());
        }

        // 计算重要性
        if (entry.getImportance() == 0) {
            entry.setImportance(0.5);
        }

        // 设置时间戳
        if (entry.getTimestamp() == 0) {
            entry.setTimestamp(System.currentTimeMillis());
        }
        entry.setLastAccessedAt(System.currentTimeMillis());

        try {
            switch (entry.getType()) {
                case EPISODIC:
                    saveEpisodic(entry);
                    break;
                case FACTUAL:
                    saveFactual(entry);
                    break;
                case PREFERENCE:
                    savePreference(entry);
                    break;
            }
            // 更新统计
            incrementStat(entry.getUserId(), entry.getType().name());
        } catch (Exception e) {
            log.error("Failed to save memory: {}", e.getMessage(), e);
        }
    }

    @Override
    public void saveBatch(List<MemoryEntry> entries) {
        for (MemoryEntry entry : entries) {
            save(entry);
        }
    }

    @Override
    public List<MemoryEntry> retrieve(String userId, String query, MemoryType type) {
        try {
            VectorSearchService searchService = vectorStoreFactory.getSearchService();
            String collection = getCollectionName(type);

            // 搜索向量
            // 注意: 这里需要先获取 query 的 embedding
            // 简化实现，实际应调用 embedding 服务
            List<Map<String, Object>> results = searchService.searchVectors(
                    embedQuery(query), collection, 10
            );

            return results.stream()
                    .map(this::mapToMemoryEntry)
                    .filter(e -> userId.equals(e.getUserId()))
                    .filter(e -> e.getType() == type)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to retrieve memories: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<MemoryEntry> retrieveAll(String userId, String query) {
        List<MemoryEntry> all = new ArrayList<>();
        for (MemoryType type : MemoryType.values()) {
            all.addAll(retrieve(userId, query, type));
        }
        return all;
    }

    @Override
    public List<MemoryEntry> getByType(String userId, MemoryType type) {
        // 简化实现: 通过 retrieve 实现
        return retrieve(userId, "", type);
    }

    @Override
    public void updateFactual(String userId, String entityKey, String entityValue) {
        MemoryEntry entry = MemoryEntry.builder()
                .id(UUID.randomUUID().toString())
                .type(MemoryType.FACTUAL)
                .userId(userId)
                .entityKey(entityKey)
                .entityValue(entityValue)
                .content(entityKey + ": " + entityValue)
                .importance(0.9)
                .timestamp(System.currentTimeMillis())
                .build();

        saveFactual(entry);
        log.info("Updated factual memory for user: {}, key: {}", userId, entityKey);
    }

    @Override
    public String getFactual(String userId, String entityKey) {
        try {
            List<MemoryEntry> entries = getByType(userId, MemoryType.FACTUAL);
            return entries.stream()
                    .filter(e -> entityKey.equals(e.getEntityKey()))
                    .findFirst()
                    .map(MemoryEntry::getEntityValue)
                    .orElse(null);
        } catch (Exception e) {
            log.error("Failed to get factual memory: {}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public void setPreference(String userId, String key, Object value) {
        try {
            String redisKey = PREFERENCE_KEY_PREFIX + userId;
            String valueStr = objectMapper.writeValueAsString(value);
            redisTemplate.opsForHash().put(redisKey, key, valueStr);
            redisTemplate.expire(redisKey, PREFERENCE_TTL_DAYS, TimeUnit.DAYS);

            // 同时保存一条记忆条目
            MemoryEntry entry = MemoryEntry.builder()
                    .type(MemoryType.PREFERENCE)
                    .userId(userId)
                    .entityKey(key)
                    .entityValue(valueStr)
                    .content("用户偏好: " + key + " = " + value)
                    .importance(0.9)
                    .timestamp(System.currentTimeMillis())
                    .build();
            savePreference(entry);

            log.debug("Set preference for user: {}, key: {}", userId, key);
        } catch (Exception e) {
            log.error("Failed to set preference: {}", e.getMessage(), e);
        }
    }

    @Override
    public Object getPreference(String userId, String key) {
        try {
            String redisKey = PREFERENCE_KEY_PREFIX + userId;
            Object value = redisTemplate.opsForHash().get(redisKey, key);
            if (value != null && value instanceof String) {
                return objectMapper.readValue((String) value, Object.class);
            }
            return value;
        } catch (Exception e) {
            log.error("Failed to get preference: {}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public Map<String, Object> getAllPreferences(String userId) {
        try {
            String redisKey = PREFERENCE_KEY_PREFIX + userId;
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(redisKey);
            Map<String, Object> result = new HashMap<>();
            for (Map.Entry<Object, Object> entry : entries.entrySet()) {
                if (entry.getValue() instanceof String) {
                    result.put((String) entry.getKey(),
                            objectMapper.readValue((String) entry.getValue(), Object.class));
                } else {
                    result.put((String) entry.getKey(), entry.getValue());
                }
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to get all preferences: {}", e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    @Override
    public void delete(String memoryId) {
        if (memoryId == null) {
            return;
        }
        try {
            // Parse memoryId format: userId:TYPE:id (e.g., "user-001:EPISODIC:mem-001")
            String[] parts = memoryId.split(":");
            if (parts.length < 3) {
                log.warn("Invalid memoryId format: {}", memoryId);
                return;
            }
            String typeStr = parts[1];
            MemoryType type = MemoryType.valueOf(typeStr);
            String collection = getCollectionName(type);

            VectorSearchService searchService = vectorStoreFactory.getSearchService();
            searchService.deleteVectors(collection, List.of(memoryId));

            // Update stats
            String userId = parts[0];
            redisTemplate.opsForValue().decrement(STATS_KEY_PREFIX + userId + ":" + typeStr);

            log.info("Deleted memory: {}", memoryId);
        } catch (Exception e) {
            log.error("Failed to delete memory: {}", memoryId, e);
        }
    }

    @Override
    public void clearUserMemory(String userId) {
        // 清空 Redis 偏好
        String redisKey = PREFERENCE_KEY_PREFIX + userId;
        redisTemplate.delete(redisKey);

        // 注意: Milvus 的数据需要单独清理
        // 简化实现，标记删除而非物理删除
        log.info("Cleared user memory from Redis for userId: {}", userId);
    }

    @Override
    public Map<String, Integer> getMemoryStats(String userId) {
        Map<String, Integer> stats = new HashMap<>();
        for (MemoryType type : MemoryType.values()) {
            String statKey = STATS_KEY_PREFIX + userId + ":" + type.name();
            String count = (String) redisTemplate.opsForValue().get(statKey);
            stats.put(type.name(), count != null ? Integer.parseInt(count) : 0);
        }
        return stats;
    }

    // ==================== 私有方法 ====================

    private void saveEpisodic(MemoryEntry entry) {
        saveToVectorStore(entry, episodicCollection);
    }

    private void saveFactual(MemoryEntry entry) {
        // 事实记忆: 先查找是否存在相同 entityKey
        List<MemoryEntry> existing = getByType(entry.getUserId(), MemoryType.FACTUAL);
        Optional<MemoryEntry> sameEntity = existing.stream()
                .filter(e -> entry.getEntityKey().equals(e.getEntityKey()))
                .findFirst();

        if (sameEntity.isPresent()) {
            // 更新: 删除旧的，插入新的
            entry.setId(sameEntity.get().getId());
            log.debug("Updating existing factual memory: {}", entry.getEntityKey());
        }

        saveToVectorStore(entry, factualCollection);
    }

    private void savePreference(MemoryEntry entry) {
        // 偏好记忆存两份: Redis (快速访问) + Milvus (长期记忆)
        saveToVectorStore(entry, "preference_memory");
    }

    private void saveToVectorStore(MemoryEntry entry, String collection) {
        try {
            VectorSearchService searchService = vectorStoreFactory.getSearchService();

            List<Map<String, Object>> docs = List.of(Map.of(
                    "id", entry.getId(),
                    "content", entry.getContent(),
                    "type", entry.getType().name(),
                    "userId", entry.getUserId(),
                    "sessionId", entry.getSessionId() != null ? entry.getSessionId() : "",
                    "entityKey", entry.getEntityKey() != null ? entry.getEntityKey() : "",
                    "entityValue", entry.getEntityValue() != null ? entry.getEntityValue() : "",
                    "metadata", entry.getMetadata() != null ? entry.getMetadata() : Collections.emptyMap()
            ));

            List<List<Float>> embeddings = List.of(embedContent(entry.getContent()));

            searchService.insertVectors(collection, docs, embeddings);
            log.debug("Saved memory to vector store: type={}, collection={}", entry.getType(), collection);
        } catch (Exception e) {
            log.error("Failed to save to vector store: {}", e.getMessage(), e);
        }
    }

    private MemoryEntry mapToMemoryEntry(Map<String, Object> map) {
        MemoryEntry entry = new MemoryEntry();
        entry.setId((String) map.get("id"));
        entry.setContent((String) map.get("content"));
        entry.setUserId((String) map.get("userId"));
        entry.setSessionId((String) map.get("sessionId"));
        entry.setEntityKey((String) map.get("entityKey"));
        entry.setEntityValue((String) map.get("entityValue"));

        String typeStr = (String) map.get("type");
        if (typeStr != null) {
            entry.setType(MemoryType.valueOf(typeStr));
        }

        entry.setMetadata((Map<String, Object>) map.get("metadata"));

        return entry;
    }

    private String getCollectionName(MemoryType type) {
        switch (type) {
            case EPISODIC:
                return episodicCollection;
            case FACTUAL:
                return factualCollection;
            case PREFERENCE:
                return "preference_memory";
            default:
                return episodicCollection;
        }
    }

    private void incrementStat(String userId, String type) {
        String statKey = STATS_KEY_PREFIX + userId + ":" + type;
        redisTemplate.opsForValue().increment(statKey);
        redisTemplate.expire(statKey, PREFERENCE_TTL_DAYS, TimeUnit.DAYS);
    }

    /**
     * 调用 Python Embedding 服务获取向量
     */
    private List<Float> embedQuery(String query) {
        try {
            return embeddingService.getEmbedding(query);
        } catch (Exception e) {
            log.error("Failed to embed query: {}", e.getMessage());
            return getFallbackEmbedding();
        }
    }

    /**
     * 调用 Python Embedding 服务获取向量
     */
    private List<Float> embedContent(String content) {
        try {
            return embeddingService.getEmbedding(content);
        } catch (Exception e) {
            log.error("Failed to embed content: {}", e.getMessage());
            return getFallbackEmbedding();
        }
    }

    /**
     * Fallback 向量
     */
    private List<Float> getFallbackEmbedding() {
        return new ArrayList<>(Collections.nCopies(1536, 0.0f));
    }
}
