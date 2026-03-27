package com.aiagent.service;

import com.aiagent.common.dto.AgentRequest;

/**
 * Prompt 构造器接口
 */
public interface PromptBuilder {

    /**
     * 构建基础 Prompt
     *
     * @param request Agent 请求
     * @return 构造后的 Prompt
     */
    String build(AgentRequest request);

    /**
     * 结合记忆上下文构建 Prompt
     *
     * @param basePrompt      基础 Prompt
     * @param memoryContext   记忆上下文
     * @return 完整 Prompt
     */
    String buildWithMemory(String basePrompt, String memoryContext);

    /**
     * 结合记忆上下文和 RAG 上下文构建 Prompt
     *
     * @param basePrompt      基础 Prompt
     * @param memoryContext   短期记忆上下文
     * @param ragContext      RAG 检索到的上下文
     * @return 完整 Prompt
     */
    String buildWithMemoryAndRag(String basePrompt, String memoryContext, String ragContext);

    /**
     * 获取系统提示词
     *
     * @param agentType Agent 类型
     * @return 系统提示词
     */
    String getSystemPrompt(String agentType);

    /**
     * 获取工具描述
     *
     * @param tools 工具名称列表
     * @return 工具描述 JSON
     */
    String getToolsDescription(String... tools);

    /**
     * 获取所有已注册工具的描述
     *
     * @return 工具描述列表
     */
    String getAllToolsDescription();
}
