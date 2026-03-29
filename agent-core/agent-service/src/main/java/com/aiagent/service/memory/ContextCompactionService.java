package com.aiagent.service.memory;

import com.aiagent.service.LLMService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 三层上下文压缩服务
 * 借鉴 learn-claude-code 的 context compaction 设计
 *
 * Layer 1 - Micro Compact: 每轮执行，替换旧 tool_result
 * Layer 2 - Auto Compact: Token 阈值触发，LLM 总结
 * Layer 3 - Manual Compact: 模型主动调用
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextCompactionService {

    private final LLMService llmService;
    private final ObjectMapper objectMapper;

    @Value("${aiagent.memory.compaction.enabled:true}")
    private boolean compactionEnabled;

    @Value("${aiagent.memory.compaction.token-threshold:8000}")
    private int tokenThreshold;

    @Value("${aiagent.memory.compaction.preserve-recent:3}")
    private int preserveRecent;

    @Value("${aiagent.memory.compaction.transcript-dir:.transcripts}")
    private String transcriptDir;

    @Value("${aiagent.memory.compaction.summaries-dir:.summaries}")
    private String summariesDir;

    private static final String TOOL_RESULT_MARKER = "[Previous: used %s]";
    private static final String COMPACTED_MARKER = "[Context compacted - see summary above]";

    /**
     * Layer 1: Micro Compact
     * 每轮执行，压缩旧的 tool_result，保留最近 N 条完整
     */
    public List<ConversationMessage> microCompact(List<ConversationMessage> messages) {
        if (!compactionEnabled || messages == null || messages.isEmpty()) {
            return messages;
        }

        List<ConversationMessage> compacted = new ArrayList<>();
        int total = messages.size();

        for (int i = 0; i < total; i++) {
            ConversationMessage msg = messages.get(i);

            // 保留最近 preserveRecent 条完整
            if (i >= total - preserveRecent) {
                compacted.add(msg);
                continue;
            }

            // 压缩旧的 tool_result
            if ("tool_result".equals(msg.getType()) && msg.getToolName() != null) {
                compacted.add(ConversationMessage.builder()
                        .role("tool")
                        .type("tool_result_compacted")
                        .toolName(msg.getToolName())
                        .content(String.format(TOOL_RESULT_MARKER, msg.getToolName()))
                        .timestamp(msg.getTimestamp())
                        .build());
            } else {
                compacted.add(msg);
            }
        }

        int saved = messages.size() - compacted.size();
        if (saved > 0) {
            log.debug("Micro compact: {} messages compacted", saved);
        }

        return compacted;
    }

    /**
     * Layer 2: Auto Compact
     * 检查是否超过 token 阈值，触发自动压缩
     */
    public CompactionResult autoCompact(String sessionId, List<ConversationMessage> messages) {
        if (!compactionEnabled || messages == null || messages.isEmpty()) {
            return CompactionResult.notNeeded(messages);
        }

        // 估算 token 数 (粗略: 1 token ≈ 4 chars)
        int estimatedTokens = estimateTokens(messages);

        if (estimatedTokens < tokenThreshold) {
            return CompactionResult.notNeeded(messages);
        }

        log.info("Auto compact triggered for session: {}, estimated tokens: {}", sessionId, estimatedTokens);

        return performCompaction(sessionId, messages, CompactionTrigger.AUTO);
    }

    /**
     * Layer 3: Manual Compact
     * 模型主动调用压缩
     */
    public CompactionResult manualCompact(String sessionId, List<ConversationMessage> messages) {
        if (!compactionEnabled || messages == null || messages.isEmpty()) {
            return CompactionResult.notNeeded(messages);
        }

        log.info("Manual compact triggered for session: {}", sessionId);

        return performCompaction(sessionId, messages, CompactionTrigger.MANUAL);
    }

    /**
     * 执行压缩核心逻辑
     */
    private CompactionResult performCompaction(String sessionId, List<ConversationMessage> messages,
                                                CompactionTrigger trigger) {
        try {
            // 1. 保存完整对话到 transcripts
            String transcriptId = saveTranscript(sessionId, messages);

            // 2. 请求 LLM 生成总结
            String summary = generateSummary(messages);

            // 3. 保存总结
            saveSummary(sessionId, transcriptId, summary, trigger);

            // 4. 构建压缩后的消息列表
            List<ConversationMessage> compacted = buildCompactedMessages(summary, messages);

            log.info("Compaction completed for session: {}, transcript: {}, summary length: {}",
                    sessionId, transcriptId, summary.length());

            return CompactionResult.builder()
                    .compacted(true)
                    .trigger(trigger)
                    .transcriptId(transcriptId)
                    .summary(summary)
                    .messages(compacted)
                    .originalCount(messages.size())
                    .compactedCount(compacted.size())
                    .build();

        } catch (Exception e) {
            log.error("Compaction failed for session: {}", sessionId, e);
            return CompactionResult.failed(messages, e.getMessage());
        }
    }

    /**
     * 生成对话总结
     */
    private String generateSummary(List<ConversationMessage> messages) {
        // 构建对话文本
        String conversation = messages.stream()
                .map(m -> {
                    String role = "user".equals(m.getRole()) ? "User" :
                            "assistant".equals(m.getRole()) ? "Assistant" : "Tool";
                    return role + ": " + m.getContent();
                })
                .collect(Collectors.joining("\n\n"));

        // 请求 LLM 总结
        String prompt = String.format("""
            Please summarize the following conversation concisely, preserving key information:

            %s

            Provide a brief summary of what was discussed and accomplished.
            """, conversation);

        try {
            return llmService.summarize(prompt);
        } catch (Exception e) {
            log.error("Failed to generate summary, using fallback", e);
            return "[Summary unavailable - conversation truncated]";
        }
    }

    /**
     * 保存完整对话记录
     */
    private String saveTranscript(String sessionId, List<ConversationMessage> messages) throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String transcriptId = sessionId + "_" + timestamp;

        Path dir = Paths.get(transcriptDir);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }

        Path file = dir.resolve(transcriptId + ".json");

        TranscriptData data = TranscriptData.builder()
                .id(transcriptId)
                .sessionId(sessionId)
                .timestamp(LocalDateTime.now().toString())
                .messages(messages)
                .build();

        Files.writeString(file, objectMapper.writeValueAsString(data));
        log.debug("Transcript saved: {}", file);

        return transcriptId;
    }

    /**
     * 保存总结
     */
    private void saveSummary(String sessionId, String transcriptId, String summary,
                             CompactionTrigger trigger) throws IOException {
        Path dir = Paths.get(summariesDir);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }

        Path file = dir.resolve(transcriptId + "_summary.txt");

        String content = String.format("""
            Session: %s
            Transcript: %s
            Timestamp: %s
            Trigger: %s

            Summary:
            %s
            """, sessionId, transcriptId, LocalDateTime.now(), trigger, summary);

        Files.writeString(file, content);
        log.debug("Summary saved: {}", file);
    }

    /**
     * 构建压缩后的消息列表
     */
    private List<ConversationMessage> buildCompactedMessages(String summary,
                                                              List<ConversationMessage> originalMessages) {
        List<ConversationMessage> compacted = new ArrayList<>();

        // 添加总结作为系统上下文
        compacted.add(ConversationMessage.builder()
                .role("system")
                .type("compaction_summary")
                .content("[Previous conversation summary]: " + summary)
                .timestamp(System.currentTimeMillis())
                .build());

        // 保留最近 preserveRecent 条完整消息
        int start = Math.max(0, originalMessages.size() - preserveRecent);
        for (int i = start; i < originalMessages.size(); i++) {
            compacted.add(originalMessages.get(i));
        }

        // 添加标记
        if (start > 0) {
            compacted.add(ConversationMessage.builder()
                    .role("system")
                    .type("compaction_marker")
                    .content(COMPACTED_MARKER)
                    .timestamp(System.currentTimeMillis())
                    .build());
        }

        return compacted;
    }

    /**
     * 估算 token 数 (粗略估计)
     */
    private int estimateTokens(List<ConversationMessage> messages) {
        if (messages == null) return 0;

        int totalChars = messages.stream()
                .mapToInt(m -> m.getContent() != null ? m.getContent().length() : 0)
                .sum();

        // 粗略: 1 token ≈ 4 字符
        return totalChars / 4;
    }

    /**
     * 对话消息
     */
    @Data
    @Builder
    public static class ConversationMessage {
        private String role;        // user, assistant, tool, system
        private String type;        // text, tool_result, tool_result_compacted, etc.
        private String content;
        private String toolName;    // for tool_result
        private long timestamp;
    }

    /**
     * 压缩结果
     */
    @Data
    @Builder
    public static class CompactionResult {
        private boolean compacted;
        private CompactionTrigger trigger;
        private String transcriptId;
        private String summary;
        private List<ConversationMessage> messages;
        private int originalCount;
        private int compactedCount;
        private String error;

        public static CompactionResult notNeeded(List<ConversationMessage> messages) {
            return CompactionResult.builder()
                    .compacted(false)
                    .messages(messages)
                    .originalCount(messages != null ? messages.size() : 0)
                    .compactedCount(messages != null ? messages.size() : 0)
                    .build();
        }

        public static CompactionResult failed(List<ConversationMessage> messages, String error) {
            return CompactionResult.builder()
                    .compacted(false)
                    .messages(messages)
                    .originalCount(messages != null ? messages.size() : 0)
                    .compactedCount(messages != null ? messages.size() : 0)
                    .error(error)
                    .build();
        }
    }

    /**
     * 压缩触发方式
     */
    public enum CompactionTrigger {
        AUTO,   // 自动触发 (token 阈值)
        MANUAL  // 手动触发 (模型调用)
    }

    /**
     * 对话记录数据
     */
    @Data
    @Builder
    private static class TranscriptData {
        private String id;
        private String sessionId;
        private String timestamp;
        private List<ConversationMessage> messages;
    }
}
