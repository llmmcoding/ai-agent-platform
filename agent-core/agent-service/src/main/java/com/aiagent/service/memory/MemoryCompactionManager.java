package com.aiagent.service.memory;

import com.aiagent.service.memory.ContextCompactionService.CompactionResult;
import com.aiagent.service.memory.ContextCompactionService.ConversationMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 记忆压缩管理器
 * 桥接 MemoryServiceImpl 和 ContextCompactionService
 * 管理会话消息列表和压缩状态
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryCompactionManager {

    private final ContextCompactionService compactionService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String MESSAGES_KEY_PREFIX = "ai:memory:messages:";
    private static final String COMPACTED_KEY_PREFIX = "ai:memory:compacted:";

    /**
     * 添加消息到会话
     * 并在添加后执行 Layer 1 (Micro Compact)
     */
    public void addMessage(String sessionId, String role, String content, String type) {
        addMessage(sessionId, role, content, type, null);
    }

    /**
     * 添加消息到会话 (带 toolName)
     */
    public void addMessage(String sessionId, String role, String content, String type, String toolName) {
        try {
            // 1. 获取现有消息
            List<ConversationMessage> messages = getMessages(sessionId);

            // 2. 添加新消息
            ConversationMessage newMsg = ConversationMessage.builder()
                    .role(role)
                    .content(content)
                    .type(type)
                    .toolName(toolName)
                    .timestamp(System.currentTimeMillis())
                    .build();
            messages.add(newMsg);

            // 3. Layer 1: Micro Compact (每轮执行)
            List<ConversationMessage> compacted = compactionService.microCompact(messages);

            // 4. 保存回 Redis
            saveMessages(sessionId, compacted);

            log.debug("Added message to session: {}, total messages: {}", sessionId, compacted.size());

        } catch (Exception e) {
            log.error("Failed to add message for session: {}", sessionId, e);
        }
    }

    /**
     * 检查并执行 Layer 2 (Auto Compact)
     * 应在每轮对话后调用
     */
    public CompactionResult checkAndAutoCompact(String sessionId) {
        try {
            List<ConversationMessage> messages = getMessages(sessionId);

            if (messages.isEmpty()) {
                return CompactionResult.notNeeded(messages);
            }

            // 检查是否需要自动压缩
            CompactionResult result = compactionService.autoCompact(sessionId, messages);

            if (result.isCompacted()) {
                // 保存压缩后的消息
                saveMessages(sessionId, result.getMessages());

                // 记录压缩状态
                redisTemplate.opsForValue().set(
                        COMPACTED_KEY_PREFIX + sessionId,
                        String.valueOf(System.currentTimeMillis()),
                        Duration.ofHours(24)
                );

                log.info("Auto compact completed for session: {}, {} -> {} messages",
                        sessionId, result.getOriginalCount(), result.getCompactedCount());
            }

            return result;

        } catch (Exception e) {
            log.error("Auto compact check failed for session: {}", sessionId, e);
            return CompactionResult.failed(getMessages(sessionId), e.getMessage());
        }
    }

    /**
     * Layer 3: Manual Compact
     * 供 CompactTool 调用
     */
    public CompactionResult manualCompact(String sessionId) {
        try {
            List<ConversationMessage> messages = getMessages(sessionId);

            CompactionResult result = compactionService.manualCompact(sessionId, messages);

            if (result.isCompacted()) {
                saveMessages(sessionId, result.getMessages());

                redisTemplate.opsForValue().set(
                        COMPACTED_KEY_PREFIX + sessionId,
                        String.valueOf(System.currentTimeMillis()),
                        Duration.ofHours(24)
                );

                log.info("Manual compact completed for session: {}, {} -> {} messages",
                        sessionId, result.getOriginalCount(), result.getCompactedCount());
            }

            return result;

        } catch (Exception e) {
            log.error("Manual compact failed for session: {}", sessionId, e);
            return CompactionResult.failed(getMessages(sessionId), e.getMessage());
        }
    }

    /**
     * 获取会话消息列表
     */
    public List<ConversationMessage> getMessages(String sessionId) {
        try {
            String key = MESSAGES_KEY_PREFIX + sessionId;
            Set<String> messageJsons = redisTemplate.opsForZSet().range(key, 0, -1);

            if (messageJsons == null || messageJsons.isEmpty()) {
                return new ArrayList<>();
            }

            return messageJsons.stream()
                    .map(json -> {
                        try {
                            return objectMapper.readValue(json, ConversationMessage.class);
                        } catch (JsonProcessingException e) {
                            log.warn("Failed to parse message: {}", json);
                            return null;
                        }
                    })
                    .filter(m -> m != null)
                    .sorted((a, b) -> Long.compare(a.getTimestamp(), b.getTimestamp()))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Failed to get messages for session: {}", sessionId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 获取格式化的对话上下文
     */
    public String getFormattedContext(String sessionId) {
        List<ConversationMessage> messages = getMessages(sessionId);

        if (messages.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (ConversationMessage msg : messages) {
            String role = switch (msg.getRole()) {
                case "user" -> "User";
                case "assistant" -> "Assistant";
                case "tool" -> "Tool";
                case "system" -> "System";
                default -> msg.getRole();
            };
            sb.append(role).append(": ").append(msg.getContent()).append("\n\n");
        }

        return sb.toString().trim();
    }

    /**
     * 获取最近 N 条消息
     */
    public List<ConversationMessage> getRecentMessages(String sessionId, int n) {
        List<ConversationMessage> messages = getMessages(sessionId);
        int start = Math.max(0, messages.size() - n);
        return messages.subList(start, messages.size());
    }

    /**
     * 清空会话消息
     */
    public void clearMessages(String sessionId) {
        String key = MESSAGES_KEY_PREFIX + sessionId;
        redisTemplate.delete(key);
        redisTemplate.delete(COMPACTED_KEY_PREFIX + sessionId);
        log.info("Cleared all messages for session: {}", sessionId);
    }

    /**
     * 获取压缩统计
     */
    public CompactionStats getStats(String sessionId) {
        List<ConversationMessage> messages = getMessages(sessionId);
        String lastCompacted = redisTemplate.opsForValue().get(COMPACTED_KEY_PREFIX + sessionId);

        int estimatedTokens = messages.stream()
                .mapToInt(m -> m.getContent() != null ? m.getContent().length() / 4 : 0)
                .sum();

        return CompactionStats.builder()
                .sessionId(sessionId)
                .messageCount(messages.size())
                .estimatedTokens(estimatedTokens)
                .lastCompactedTime(lastCompacted != null ? Long.parseLong(lastCompacted) : 0)
                .hasSummary(messages.stream().anyMatch(m -> "compaction_summary".equals(m.getType())))
                .build();
    }

    /**
     * 保存消息列表到 Redis
     */
    private void saveMessages(String sessionId, List<ConversationMessage> messages) {
        try {
            String key = MESSAGES_KEY_PREFIX + sessionId;

            // 清除旧数据
            redisTemplate.delete(key);

            // 批量添加
            for (ConversationMessage msg : messages) {
                String json = objectMapper.writeValueAsString(msg);
                redisTemplate.opsForZSet().add(key, json, msg.getTimestamp());
            }

            // 设置 TTL
            redisTemplate.expire(key, Duration.ofHours(24));

        } catch (JsonProcessingException e) {
            log.error("Failed to save messages for session: {}", sessionId, e);
        }
    }

    /**
     * 压缩统计
     */
    @lombok.Data
    @lombok.Builder
    public static class CompactionStats {
        private String sessionId;
        private int messageCount;
        private int estimatedTokens;
        private long lastCompactedTime;
        private boolean hasSummary;
    }
}
