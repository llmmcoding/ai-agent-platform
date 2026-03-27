package com.aiagent.service;

import com.aiagent.common.dto.AgentRequest;
import com.aiagent.common.dto.AgentResponse;

/**
 * LLM 服务接口
 */
public interface LLMService {

    /**
     * 调用 LLM
     *
     * @param prompt   提示词
     * @param request  Agent 请求
     * @return LLM 响应
     */
    AgentResponse call(String prompt, AgentRequest request);

    /**
     * 流式调用 LLM
     *
     * @param prompt   提示词
     * @param request  Agent 请求
     * @return 流式响应
     */
    String streamCall(String prompt, AgentRequest request);

    /**
     * 选择 LLM Provider
     *
     * @param request Agent 请求
     * @return 选择的 Provider
     */
    String selectProvider(AgentRequest request);

    /**
     * 生成摘要
     *
     * @param content 需要摘要的内容
     * @return 摘要结果
     */
    String summarize(String content);
}
