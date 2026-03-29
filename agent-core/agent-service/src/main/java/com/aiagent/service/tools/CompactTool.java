package com.aiagent.service.tools;

import com.aiagent.common.dto.AgentRequest;
import com.aiagent.service.memory.ContextCompactionService;
import com.aiagent.service.memory.ContextCompactionService.CompactionResult;
import com.aiagent.service.memory.MemoryCompactionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Compact Tool - 手动触发上下文压缩
 *
 * 供模型主动调用，当模型判断需要压缩历史对话时调用此工具。
 * 这是三层压缩的 Layer 3 (Manual Compact)。
 *
 * 使用场景:
 * - 对话历史过长影响理解
 * - 需要释放上下文窗口
 * - 用户要求总结前面内容
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompactTool {

    private final MemoryCompactionManager compactionManager;

    /**
     * Tool 定义 (用于 ToolRegistry 注册)
     */
    public static final String TOOL_NAME = "compact_context";
    public static final String TOOL_DESCRIPTION = """
        Compact the conversation history to free up context window.
        Use this when:
        - The conversation is getting too long
        - You need to summarize previous discussions
        - Context window is running out
        - User asks to summarize what we've done so far

        The tool will:
        1. Save the full conversation transcript
        2. Generate a summary using LLM
        3. Replace old messages with the summary
        4. Keep the most recent messages intact
        """;

    /**
     * 执行压缩
     *
     * @param input 输入参数 (包含 reason)
     * @param request AgentRequest (包含 sessionId)
     * @return 压缩结果
     */
    public CompletableFuture<String> execute(Map<String, Object> input, AgentRequest request) {
        String sessionId = request.getSessionId();
        String reason = (String) input.getOrDefault("reason", "manual trigger");

        log.info("CompactTool triggered for session: {}, reason: {}", sessionId, reason);

        try {
            CompactionResult result = compactionManager.manualCompact(sessionId);

            if (result.isCompacted()) {
                String response = String.format("""
                    Context compacted successfully.
                    - Original messages: %d
                    - Compacted to: %d messages
                    - Summary length: %d characters
                    - Transcript saved: %s

                    You can now continue with the compressed context.

                    Summary preview:
                    %s
                    """,
                    result.getOriginalCount(),
                    result.getCompactedCount(),
                    result.getSummary() != null ? result.getSummary().length() : 0,
                    result.getTranscriptId(),
                    result.getSummary() != null ?
                        result.getSummary().substring(0, Math.min(200, result.getSummary().length())) + "..."
                        : "N/A"
                );
                return CompletableFuture.completedFuture(response);
            } else {
                return CompletableFuture.completedFuture(
                    "Context compaction not needed or failed: " + result.getError()
                );
            }

        } catch (Exception e) {
            log.error("CompactTool failed for session: {}", sessionId, e);
            return CompletableFuture.completedFuture(
                "Error compacting context: " + e.getMessage()
            );
        }
    }
}
