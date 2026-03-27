package com.aiagent.service;

import com.aiagent.common.dto.AgentRequest;
import com.aiagent.common.dto.AgentResponse;

/**
 * ReAct 推理引擎接口
 */
public interface ReActEngine {

    /**
     * 执行 ReAct 推理循环
     *
     * @param prompt  提示词
     * @param request Agent 请求
     * @return Agent 响应
     */
    AgentResponse execute(String prompt, AgentRequest request);

    /**
     * 执行单步推理
     *
     * @param thought    当前思考
     * @param action     要执行的动作
     * @param actionInput 动作输入
     * @param request    Agent 请求
     * @return 执行结果
     */
    String step(String thought, String action, String actionInput, AgentRequest request);
}
