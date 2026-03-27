package com.aiagent.service;

import com.aiagent.common.dto.AgentRequest;
import com.aiagent.common.dto.AgentResponse;
import reactor.core.publisher.Mono;

/**
 * Agent 服务接口
 */
public interface AgentService {

    /**
     * 同步执行 Agent
     *
     * @param request Agent 请求
     * @return Agent 响应
     */
    Mono<AgentResponse> invoke(AgentRequest request);

    /**
     * 异步执行 Agent
     *
     * @param request Agent 请求
     * @return 任务 ID
     */
    Mono<String> invokeAsync(AgentRequest request);

    /**
     * 查询异步任务状态
     *
     * @param taskId 任务 ID
     * @return Agent 响应
     */
    Mono<AgentResponse> getTaskStatus(String taskId);

    /**
     * 流式执行 Agent
     *
     * @param request Agent 请求
     * @return SSE 流
     */
    Mono<String> invokeStream(AgentRequest request);
}
