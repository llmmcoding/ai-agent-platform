package com.aiagent.service.impl;

import com.aiagent.common.Constants;
import com.aiagent.service.LLMService;
import com.aiagent.service.MemoryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 记忆服务实现
 * 短期记忆: Redis
 * 长期记忆: Milvus (通过 Kafka 异步写入)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryServiceImpl implements MemoryService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final ToolKafkaClient toolKafkaClient;
    private final LLMService llmService;

    @Value("${aiagent.memory.short-term.ttl:3600}")
    private int shortTermTtl;

    @Value("${aiagent.python-worker.url:http://localhost:8001}")
    private String pythonWorkerUrl;

    @Value("${aiagent.memory.long-term.collection:long_term_memory}")
    private String longTermCollection;

    @Value("${aiagent.memory.short-term.max-size:100}")
    private int maxMemorySize;

    @Value("${aiagent.memory.summary-trigger-rounds:10}")
    private int summaryTriggerRounds;

    /**
     * 短期记忆 Key 格式: ai:memory:session:{sessionId}
     * 存储格式: Redis ZSET
     *   - Score: timestamp (时间戳，用于排序和过期管理)
     *   - Member: MemoryEntry JSON 字符串
     * 优势: ZADD 原子写入，ZREMRANGEBYRANK 高效裁剪，ZRANGEBYSCORE 支持时间范围查询
     */
    private static final String SHORT_TERM_KEY_PREFIX = Constants.RedisKey.MEMORY_PREFIX + "session:";

    /**
     * Round 计数器 Key 格式: ai:memory:rounds:{sessionId}
     * 用于追踪对话轮次，触发摘要
     */
    private static final String ROUNDS_KEY_PREFIX = Constants.RedisKey.MEMORY_PREFIX + "rounds:";

    /**
     * Lua 脚本: 批量 ZADD + 条件裁剪 + 设置 TTL
     * 原子操作避免 race condition
     */
    private static final String BATCH_ZADD_SCRIPT = """
        local key = KEYS[1]
        local entries = cjson.decode(ARGV[1])
        local maxSize = tonumber(ARGV[2])
        local expire = tonumber(ARGV[3])

        -- 批量 ZADD
        for i, entry in ipairs(entries) do
            redis.call('ZADD', key, entry.score, entry.json)
        end

        -- 设置 TTL
        redis.call('EXPIRE', key, expire)

        -- 条件裁剪: 只在需要时裁剪
        local size = redis.call('ZCARD', key)
        if size > maxSize then
            redis.call('ZREMRANGEBYRANK', key, 0, size - maxSize - 1)
        end

        return size
        """;

    private static final DefaultRedisScript<Long> BATCH_ZADD_SCRIPT_INSTANCE;

    static {
        BATCH_ZADD_SCRIPT_INSTANCE = new DefaultRedisScript<>();
        BATCH_ZADD_SCRIPT_INSTANCE.setScriptText(BATCH_ZADD_SCRIPT);
        BATCH_ZADD_SCRIPT_INSTANCE.setResultType(Long.class);
    }

    @Override
    public String getShortTermMemory(String sessionId) {
        try {
            String key = SHORT_TERM_KEY_PREFIX + sessionId;
            Set<String> entriesJson = redisTemplate.opsForZSet().reverseRange(key, 0, -1);

            if (entriesJson == null || entriesJson.isEmpty()) {
                return "";
            }

            List<MemoryEntry> entries = new ArrayList<>();
            for (String entryJson : entriesJson) {
                try {
                    entries.add(objectMapper.readValue(entryJson, MemoryEntry.class));
                } catch (JsonProcessingException e) {
                    log.warn("Failed to parse memory entry: {}", entryJson, e);
                }
            }

            // 构建上下文
            return buildContextFromEntries(entries);
        } catch (Exception e) {
            log.error("Failed to get short-term memory for session: {}", sessionId, e);
            return "";
        }
    }

    @Override
    public void updateShortTermMemory(String sessionId, String userInput, String agentOutput) {
        try {
            String key = SHORT_TERM_KEY_PREFIX + sessionId;
            String roundsKey = ROUNDS_KEY_PREFIX + sessionId;
            long timestamp = System.currentTimeMillis();

            // 1. INCR round counter
            Long roundCount = redisTemplate.opsForValue().increment(roundsKey);
            if (roundCount == null) {
                roundCount = 1L;
            }
            // 设置 round counter TTL
            redisTemplate.expire(roundsKey, Duration.ofSeconds(shortTermTtl));

            // 2. 检查是否需要触发摘要 (每 N 轮触发一次)
            boolean shouldSummarize = (roundCount % summaryTriggerRounds == 0);

            if (shouldSummarize) {
                // 即将触发摘要，只添加不裁剪（让 triggerSummary 清空）
                MemoryEntry userEntry = new MemoryEntry("user", userInput, timestamp);
                MemoryEntry assistantEntry = new MemoryEntry("assistant", agentOutput, timestamp);

                List<Map<String, Object>> entries = List.of(
                        Map.of("score", (double) timestamp, "json", objectMapper.writeValueAsString(userEntry)),
                        Map.of("score", (double) timestamp, "json", objectMapper.writeValueAsString(assistantEntry))
                );

                redisTemplate.execute(BATCH_ZADD_SCRIPT_INSTANCE,
                        List.of(key),
                        objectMapper.writeValueAsString(entries),
                        String.valueOf(maxMemorySize),
                        String.valueOf(shortTermTtl)
                );

                // 异步触发摘要（摘要完成后会清空短期记忆）
                final long capturedRoundCount = roundCount;
                CompletableFuture.runAsync(() -> {
                    log.info("Triggering summary for session {} at round {}", sessionId, capturedRoundCount);
                    triggerSummary(sessionId);
                });

                log.debug("Updated short-term memory for session: {}, round: {}, summary queued", sessionId, roundCount);
            } else {
                // 正常情况: 批量 ZADD + 条件裁剪
                MemoryEntry userEntry = new MemoryEntry("user", userInput, timestamp);
                MemoryEntry assistantEntry = new MemoryEntry("assistant", agentOutput, timestamp);

                List<Map<String, Object>> entries = List.of(
                        Map.of("score", (double) timestamp, "json", objectMapper.writeValueAsString(userEntry)),
                        Map.of("score", (double) timestamp, "json", objectMapper.writeValueAsString(assistantEntry))
                );

                Long currentSize = redisTemplate.execute(BATCH_ZADD_SCRIPT_INSTANCE,
                        List.of(key),
                        objectMapper.writeValueAsString(entries),
                        String.valueOf(maxMemorySize),
                        String.valueOf(shortTermTtl)
                );

                log.debug("Updated short-term memory for session: {}, round: {}, total entries: {}", sessionId, roundCount, currentSize);
            }
        } catch (Exception e) {
            log.error("Failed to update short-term memory for session: {}", sessionId, e);
        }
    }

    @Override
    public String getLongTermMemory(String userId, String query) {
        try {
            log.debug("Long-term memory query for user: {}, query: {}", userId, query);

            // 调用 Python Worker 的 RAG API 进行向量检索
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("query", query);
            requestBody.put("collection", longTermCollection);
            requestBody.put("top_k", 5);

            String response = webClient.post()
                    .uri(pythonWorkerUrl + "/api/v1/rag/query")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));

            // 解析 RAG 结果，提取 content 组成上下文
            if (response != null && !response.isEmpty()) {
                return parseRagResponse(response);
            }
            return "";
        } catch (Exception e) {
            log.error("Failed to get long-term memory for user: {}, query: {}", userId, query, e);
            return "";
        }
    }

    @Override
    public void saveLongTermMemory(String userId, String content) {
        try {
            log.debug("Saving long-term memory for user: {}, content length: {}", userId, content.length());

            // 异步执行，通过 Kafka 发送到 Python Worker
            CompletableFuture.runAsync(() -> {
                try {
                    // 通过 ToolKafkaClient 发送记忆保存任务
                    Map<String, Object> input = new HashMap<>();
                    input.put("user_id", userId);
                    input.put("content", content);
                    input.put("collection", longTermCollection);

                    // 使用 Kafka 异步发送
                    toolKafkaClient.sendToolRequest("rag_index_memory", input, userId);

                    log.info("Long-term memory save queued for user: {}", userId);
                } catch (Exception e) {
                    log.error("Failed to save long-term memory for user: {}", userId, e);
                }
            });
        } catch (Exception e) {
            log.error("Failed to initiate long-term memory save for user: {}", userId, e);
        }
    }

    /**
     * 解析 RAG API 响应，提取内容
     */
    private String parseRagResponse(String response) {
        try {
            // response 格式: [{"id": "...", "content": "...", "score": 0.9, "metadata": {...}}]
            List<Map> results = objectMapper.readValue(response,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));

            if (results == null || results.isEmpty()) {
                return "";
            }

            StringBuilder context = new StringBuilder();
            for (Map result : results) {
                String content = (String) result.get("content");
                if (content != null && !content.isEmpty()) {
                    context.append(content).append("\n\n");
                }
            }
            return context.toString().trim();
        } catch (Exception e) {
            log.warn("Failed to parse RAG response: {}", response, e);
            return "";
        }
    }

    @Override
    public void triggerSummary(String sessionId) {
        try {
            String memory = getShortTermMemory(sessionId);
            if (memory.isEmpty()) {
                log.info("No memory to summarize for session: {}", sessionId);
                return;
            }

            // 调用 LLM 生成摘要
            String summary = llmService.summarize(memory);
            log.info("Generated summary for session: {}, length: {}", sessionId, summary.length());

            // 保存到长期记忆 (使用 sessionId 作为 userId)
            saveLongTermMemory(sessionId, summary);

            // 清空短期记忆，避免重复上下文
            clearMemory(sessionId);

            // 重置 round counter
            String roundsKey = ROUNDS_KEY_PREFIX + sessionId;
            redisTemplate.delete(roundsKey);

            log.info("Memory summary completed and short-term memory cleared for session: {}", sessionId);
        } catch (Exception e) {
            log.error("Failed to generate memory summary for session: {}", sessionId, e);
        }
    }

    @Override
    public void clearMemory(String sessionId) {
        try {
            String key = SHORT_TERM_KEY_PREFIX + sessionId;
            redisTemplate.delete(key);
            log.info("Cleared memory for session: {}", sessionId);
        } catch (Exception e) {
            log.error("Failed to clear memory for session: {}", sessionId, e);
        }
    }

    /**
     * 从记忆条目构建上下文
     */
    private String buildContextFromEntries(List<MemoryEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (MemoryEntry entry : entries) {
            String role = entry.getRole().equals("user") ? "User" : "Assistant";
            sb.append(role).append(": ").append(entry.getContent()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 记忆条目
     */
    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @lombok.Builder
    private static class MemoryEntry {
        private String role;       // "user" or "assistant"
        private String content;
        private long timestamp;
    }
}
