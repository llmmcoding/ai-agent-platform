package com.aiagent.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Agent 执行响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 响应 ID */
    private String responseId;

    /** 会话 ID */
    private String sessionId;

    /** Agent 输出内容 */
    private String content;

    /** 是否完成 */
    private Boolean completed;

    /** 执行状态 */
    private String status;

    /** 使用的 LLM Provider */
    private String llmProvider;

    /** Token 使用量 */
    private TokenUsage tokenUsage;

    /** 工具调用历史 */
    private List<ToolCallRecord> toolCalls;

    /** 额外元数据 */
    private Map<String, Object> metadata;

    /** 错误信息 */
    private String error;

    /** 执行耗时(ms) */
    private Long latencyMs;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TokenUsage implements Serializable {
        private static final long serialVersionUID = 1L;
        private Integer promptTokens;
        private Integer completionTokens;
        private Integer totalTokens;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCallRecord implements Serializable {
        private static final long serialVersionUID = 1L;
        private String toolName;
        private String input;
        private String output;
        private Long durationMs;
        private String status;
    }
}
