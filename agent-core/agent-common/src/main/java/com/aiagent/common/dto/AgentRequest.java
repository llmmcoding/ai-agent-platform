package com.aiagent.common.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Agent 执行请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 会话 ID */
    private String sessionId;

    /** 用户 ID */
    private String userId;

    /** 用户输入 */
    @NotBlank(message = "用户输入不能为空")
    private String userInput;

    /** Agent 类型 */
    private String agentType;

    /** 指定使用的 LLM Provider */
    private String llmProvider;

    /** 额外参数 */
    private Map<String, Object> parameters;

    /** 是否启用 RAG */
    private Boolean enableRag;

    /** RAG collection */
    private String ragCollection;

    /** 启用工具列表 */
    private List<String> enabledTools;

    /** 系统提示词覆盖 */
    private String systemPrompt;

    /** 最大迭代次数 */
    private Integer maxIterations;

    /** 追踪 ID */
    private String traceId;
}
