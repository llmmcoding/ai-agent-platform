package com.aiagent.service.impl;

import com.aiagent.common.dto.AgentRequest;
import com.aiagent.common.exception.AgentException;
import com.aiagent.service.PromptBuilder;
import com.aiagent.service.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Prompt 构造器实现
 * 支持动态组装 System Prompt、Rules、Memory、Tools
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptBuilderImpl implements PromptBuilder {

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    /**
     * 默认 Agent 类型对应的系统提示词
     */
    private static final Map<String, String> DEFAULT_SYSTEM_PROMPTS = new HashMap<>();

    static {
        DEFAULT_SYSTEM_PROMPTS.put("assistant", """
                You are a helpful AI assistant.

                You have access to various tools to help you answer user questions.
                When you need to use a tool, follow the required format.

                Guidelines:
                - Be helpful, harmless, and honest
                - Use tools when needed to provide accurate information
                - If a tool fails, explain the error and suggest alternatives
                - Think step by step for complex problems
                """);

        DEFAULT_SYSTEM_PROMPTS.put("coder", """
                You are an expert programmer assistant.

                You help users write, debug, and optimize code.
                You have access to tools for code execution and web search.

                Guidelines:
                - Write clean, efficient, and well-documented code
                - Follow best practices and design patterns
                - Explain your reasoning when writing complex logic
                - Help users understand and fix bugs
                """);

        DEFAULT_SYSTEM_PROMPTS.put("analyst", """
                You are a data analysis expert.

                You help users analyze data, generate insights, and create visualizations.

                Guidelines:
                - Provide accurate and objective analysis
                - Use clear visualizations and explanations
                - Consider multiple perspectives
                - Highlight key findings and actionable insights
                """);
    }

    @Override
    public String build(AgentRequest request) {
        StringBuilder prompt = new StringBuilder();

        // 1. 添加系统提示词
        String systemPrompt = getSystemPrompt(request.getAgentType());
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isEmpty()) {
            systemPrompt = request.getSystemPrompt();
        }
        prompt.append("System: ").append(systemPrompt).append("\n\n");

        // 2. 添加工具描述
        if (request.getEnabledTools() != null && !request.getEnabledTools().isEmpty()) {
            String toolsDescription = getToolsDescription(
                    request.getEnabledTools().toArray(new String[0])
            );
            prompt.append("Tools:\n").append(toolsDescription).append("\n\n");
        }

        // 3. 添加用户输入
        prompt.append("User: ").append(request.getUserInput());

        return prompt.toString();
    }

    @Override
    public String buildWithMemory(String basePrompt, String memoryContext) {
        if (memoryContext == null || memoryContext.isEmpty()) {
            return basePrompt;
        }

        // 在 System Prompt 后插入记忆上下文
        int systemEndIdx = basePrompt.indexOf("\n\nUser:");
        if (systemEndIdx == -1) {
            systemEndIdx = basePrompt.indexOf("\n\n");
            if (systemEndIdx == -1) {
                systemEndIdx = basePrompt.length();
            }
        }

        String memorySection = "\n\nRelevant Context from Memory:\n" + memoryContext + "\n";
        return basePrompt.substring(0, systemEndIdx) + memorySection + basePrompt.substring(systemEndIdx);
    }

    @Override
    public String buildWithMemoryAndRag(String basePrompt, String memoryContext, String ragContext) {
        String prompt = buildWithMemory(basePrompt, memoryContext);

        if (ragContext == null || ragContext.isEmpty()) {
            return prompt;
        }

        // 在记忆上下文后插入 RAG 知识库上下文
        String ragSection = "\n\nKnowledge Base:\n" + ragContext + "\n";
        int userIdx = prompt.indexOf("\nUser:");
        if (userIdx == -1) {
            return prompt + ragSection;
        }

        return prompt.substring(0, userIdx) + ragSection + prompt.substring(userIdx);
    }

    @Override
    public String getSystemPrompt(String agentType) {
        if (agentType == null || agentType.isEmpty()) {
            agentType = "assistant";
        }
        return DEFAULT_SYSTEM_PROMPTS.getOrDefault(agentType, DEFAULT_SYSTEM_PROMPTS.get("assistant"));
    }

    @Override
    public String getToolsDescription(String... tools) {
        if (tools == null || tools.length == 0) {
            return getAllToolsDescription();
        }

        StringBuilder sb = new StringBuilder();
        for (String toolName : tools) {
            String desc = toolRegistry.getToolDescription(toolName);
            if (desc != null) {
                sb.append(desc).append("\n");
            }
        }
        return sb.toString();
    }

    @Override
    public String getAllToolsDescription() {
        return toolRegistry.getAllToolsDescription();
    }

    /**
     * 构建 ReAct 格式的提示词
     */
    public String buildReActPrompt(AgentRequest request) {
        StringBuilder prompt = new StringBuilder();

        // 系统提示词
        prompt.append("System: You are an AI agent that uses the ReAct (Reasoning + Acting) pattern.\n");
        prompt.append("For each user query:\n");
        prompt.append("1. Think about what you need to do (Thought)\n");
        prompt.append("2. Take an action if needed (Action)\n");
        prompt.append("3. Observe the result (Observation)\n");
        prompt.append("4. Repeat until you can provide the final answer (Final)\n\n");

        // 工具描述
        String toolsDesc = toolRegistry.getAllToolsDescription();
        if (!toolsDesc.isEmpty()) {
            prompt.append("Available Tools:\n").append(toolsDesc).append("\n");
        }

        // 格式说明
        prompt.append("""
                Response Format:
                Thought: <your reasoning>
                Action: <tool_name> if using a tool, or "null" if done
                Action Input: <tool input JSON> if using a tool, or "null"
                Observation: <result of tool execution> if action was taken

                ... (can repeat Thought/Action/Action Input/Observation cycles)

                Final: <your final answer to the user>
                """);

        // 用户输入
        prompt.append("\nUser: ").append(request.getUserInput());

        return prompt.toString();
    }

    /**
     * 解析 ReAct 输出
     */
    public ReActStep parseReActOutput(String output) {
        ReActStep step = new ReActStep();

        String[] lines = output.split("\n");
        for (String line : lines) {
            if (line.startsWith("Thought:")) {
                step.setThought(line.substring(8).trim());
            } else if (line.startsWith("Action:")) {
                String action = line.substring(7).trim();
                step.setAction("null".equalsIgnoreCase(action) ? null : action);
            } else if (line.startsWith("Action Input:")) {
                String input = line.substring(13).trim();
                step.setActionInput("null".equalsIgnoreCase(input) ? null : input);
            } else if (line.startsWith("Observation:")) {
                step.setObservation(line.substring(12).trim());
            } else if (line.startsWith("Final:")) {
                step.setFinalAnswer(line.substring(6).trim());
            }
        }

        return step;
    }

    /**
     * ReAct 步骤
     */
    @lombok.Data
    public static class ReActStep {
        private String thought;
        private String action;
        private String actionInput;
        private String observation;
        private String finalAnswer;
    }
}
