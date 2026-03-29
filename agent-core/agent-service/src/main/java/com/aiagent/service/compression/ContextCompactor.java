package com.aiagent.service.compression;

import com.aiagent.service.LLMService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 三层上下文压缩机制
 *
 * Layer 1 - micro_compact: 每轮静默将 tool result 替换为 [Used {tool_name}]
 * Layer 2 - auto_compact: token 阈值触发，保存完整 transcript 到文件，summary 注入
 * Layer 3 - compact tool: 模型可手动触发立即压缩
 *
 * 借鉴 learn-claude-code s06 的三层压缩设计
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextCompactor {

    private final LLMService llmService;
    private final ObjectMapper objectMapper;

    /**
     * Layer 1 micro_compact 替换模式
     * 匹配 "Observation: <tool_result>" 中的长输出
     */
    private static final Pattern OBSERVATION_PATTERN = Pattern.compile(
            "(Observation: ).*?(?=\\n(?:Thought:|Action:|Final:|User:|System:)|$)",
            Pattern.DOTALL
    );

    /**
     * Layer 2 阈值配置
     */
    @Value("${aiagent.context-compaction.token-threshold:32000}")
    private int tokenThreshold;

    @Value("${aiagent.context-compaction.micro-compact-min-length:500}")
    private int microCompactMinLength;

    @Value("${aiagent.context-compaction.enabled:true}")
    private boolean enabled;

    @Value("${aiagent.context-compaction.transcript-dir:${java.io.tmpdir}/ai-agent/transcripts}")
    private String transcriptDir;

    /**
     * 当前对话的 transcript 记录 (sessionId -> transcript)
     */
    private final java.util.Map<String, TranscriptRecord> transcriptCache = new java.util.concurrent.ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 确保 transcript 目录存在
        try {
            Files.createDirectories(Paths.get(transcriptDir));
            log.info("ContextCompactor initialized, transcript dir: {}", transcriptDir);
        } catch (IOException e) {
            log.warn("Failed to create transcript dir: {}", e.getMessage());
        }
    }

    /**
     * Layer 1: Micro Compact
     * 将长 tool result 静默替换为 [Used {tool_name}]
     *
     * @param fullPrompt 完整 prompt
     * @param toolName 工具名称
     * @param toolResult 工具执行结果
     * @return 压缩后的 prompt
     */
    public String microCompact(String fullPrompt, String toolName, String toolResult) {
        if (!enabled || toolResult == null || toolResult.length() < microCompactMinLength) {
            return fullPrompt;
        }

        // 匹配最后的 Observation 部分并替换
        String lastObservationPattern = "Observation: " + Pattern.quote(toolResult);
        String compactReplacement = "Observation: [Used " + toolName + "]";

        if (fullPrompt.contains(toolResult)) {
            String compactPrompt = fullPrompt.replace(lastObservationPattern, compactReplacement);
            if (!compactPrompt.equals(fullPrompt)) {
                log.debug("Micro compact: replaced {} char tool result with [Used {}]",
                        toolResult.length(), toolName);
                return compactPrompt;
            }
        }

        return fullPrompt;
    }

    /**
     * Layer 2: Auto Compact
     * 当 token 超过阈值时，保存完整 transcript 并注入 summary
     *
     * @param sessionId 会话 ID
     * @param fullPrompt 完整 prompt
     * @param currentRound 当前轮次
     * @return 压缩后的 prompt
     */
    public String autoCompact(String sessionId, String fullPrompt, int currentRound) {
        if (!enabled) {
            return fullPrompt;
        }

        int estimatedTokens = estimateTokens(fullPrompt);
        if (estimatedTokens < tokenThreshold) {
            return fullPrompt;
        }

        log.info("Auto compact triggered for session {}, estimated tokens: {}, threshold: {}",
                sessionId, estimatedTokens, tokenThreshold);

        // 1. 保存完整 transcript 到文件
        String transcriptFile = saveTranscript(sessionId, fullPrompt, currentRound);

        // 2. 生成 summary
        String summary = generateSummary(fullPrompt, sessionId);

        // 3. 构建压缩后的 prompt
        String compactPrompt = buildCompactPrompt(sessionId, summary, transcriptFile, currentRound);

        // 4. 清理旧记录
        transcriptCache.remove(sessionId);

        log.info("Auto compact completed for session {}, summary length: {}, new prompt tokens: {}",
                sessionId, summary.length(), estimateTokens(compactPrompt));

        return compactPrompt;
    }

    /**
     * Layer 3: 手动压缩工具
     * 模型可调用此方法触发立即压缩
     *
     * @param sessionId 会话 ID
     * @param fullPrompt 完整 prompt
     * @param currentRound 当前轮次
     * @return 压缩结果包含新 prompt 和摘要
     */
    public CompactResult manualCompact(String sessionId, String fullPrompt, int currentRound) {
        log.info("Manual compact triggered for session {} at round {}", sessionId, currentRound);

        // 1. 保存完整 transcript
        String transcriptFile = saveTranscript(sessionId, fullPrompt, currentRound);

        // 2. 生成 summary
        String summary = generateSummary(fullPrompt, sessionId);

        // 3. 构建压缩后的 prompt
        String compactPrompt = buildCompactPrompt(sessionId, summary, transcriptFile, currentRound);

        // 4. 清理
        transcriptCache.remove(sessionId);

        return CompactResult.builder()
                .newPrompt(compactPrompt)
                .summary(summary)
                .transcriptFile(transcriptFile)
                .previousTokens(estimateTokens(fullPrompt))
                .newTokens(estimateTokens(compactPrompt))
                .build();
    }

    /**
     * 检查是否需要 auto compact
     */
    public boolean needsAutoCompact(String fullPrompt) {
        return enabled && estimateTokens(fullPrompt) >= tokenThreshold;
    }

    /**
     * 估算 token 数量 (粗略估算: 1 token ≈ 4 字符)
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // 中文按字符计，英文按单词计
        // 粗略估算: 1 token ≈ 4 characters for Chinese, or ≈ 1.3 words for English
        int chineseChars = (int) text.chars().filter(c -> c > 0x4E00 && c < 0x9FA5).count();
        int otherChars = text.length() - chineseChars;
        return (int) (chineseChars / 2 + otherChars / 4);
    }

    /**
     * 保存完整 transcript 到文件
     */
    private String saveTranscript(String sessionId, String fullPrompt, int currentRound) {
        try {
            String fileName = String.format("transcript_%s_%d_%d.txt",
                    sessionId, currentRound, Instant.now().toEpochMilli());
            Path filePath = Paths.get(transcriptDir, fileName);

            TranscriptRecord record = TranscriptRecord.builder()
                    .sessionId(sessionId)
                    .round(currentRound)
                    .content(fullPrompt)
                    .createdAt(Instant.now())
                    .filePath(filePath.toString())
                    .build();

            transcriptCache.put(sessionId, record);
            Files.writeString(filePath, fullPrompt);

            log.debug("Transcript saved to: {}", filePath);
            return filePath.toString();
        } catch (IOException e) {
            log.error("Failed to save transcript for session {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 生成 summary
     */
    private String generateSummary(String fullPrompt, String sessionId) {
        try {
            // 使用 LLM 服务生成摘要
            String summary = llmService.summarize(fullPrompt);
            return summary;
        } catch (Exception e) {
            log.error("Failed to generate summary for session {}: {}", sessionId, e.getMessage());
            // 降级: 使用简单的首尾提取
            return generateFallbackSummary(fullPrompt);
        }
    }

    /**
     * 降级摘要: 取开头和结尾
     */
    private String generateFallbackSummary(String fullPrompt) {
        int maxLen = 500;
        if (fullPrompt.length() <= maxLen) {
            return fullPrompt;
        }
        String head = fullPrompt.substring(0, maxLen / 2);
        String tail = fullPrompt.substring(fullPrompt.length() - maxLen / 2);
        return head + "\n...\n[内容已压缩]\n...\n" + tail;
    }

    /**
     * 构建压缩后的 prompt
     */
    private String buildCompactPrompt(String sessionId, String summary, String transcriptFile, int currentRound) {
        StringBuilder sb = new StringBuilder();

        // 保留系统提示词部分 (通过 [Compressed Summary] 标记之前的上下文)
        sb.append("[Compressed Summary from Previous Conversation]\n");
        sb.append("Round ").append(currentRound).append(" summary:\n");
        sb.append(summary).append("\n\n");
        sb.append("Full transcript saved to: ").append(transcriptFile != null ? transcriptFile : "N/A").append("\n\n");
        sb.append("=" .repeat(50)).append("\n\n");
        sb.append("[Continuing from round ").append(currentRound + 1).append("]\n\n");

        // 追加压缩标记，提示 LLM 继续
        sb.append("Reminder: Previous conversation context has been compressed. ");
        sb.append("You have a summary of earlier interactions. Work from this point forward.\n\n");

        return sb.toString();
    }

    /**
     * 压缩结果
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class CompactResult {
        private String newPrompt;
        private String summary;
        private String transcriptFile;
        private int previousTokens;
        private int newTokens;
    }

    /**
     * Transcript 记录
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    private static class TranscriptRecord {
        private String sessionId;
        private int round;
        private String content;
        private Instant createdAt;
        private String filePath;
    }
}
