package com.aiagent.service;

import com.aiagent.common.dto.AgentRequest;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 工具注册器接口
 */
public interface ToolRegistry {

    /**
     * 注册工具
     *
     * @param name        工具名称
     * @param description 工具描述
     * @param type        工具类型 (java/python/external)
     * @param executor    工具执行器
     */
    void register(String name, String description, String type, ToolExecutor executor);

    /**
     * 执行工具
     *
     * @param name     工具名称
     * @param input    输入参数
     * @param request  Agent 请求
     * @return 工具执行结果
     */
    CompletableFuture<String> execute(String name, Map<String, Object> input, AgentRequest request);

    /**
     * 获取工具描述
     *
     * @param name 工具名称
     * @return 工具描述 JSON
     */
    String getToolDescription(String name);

    /**
     * 获取所有已注册工具的描述
     *
     * @return 工具描述列表
     */
    String getAllToolsDescription();

    /**
     * 检查工具是否存在
     *
     * @param name 工具名称
     * @return 是否存在
     */
    boolean hasTool(String name);

    /**
     * 工具执行器接口
     */
    @FunctionalInterface
    interface ToolExecutor {
        CompletableFuture<String> execute(Map<String, Object> input, AgentRequest request);
    }
}
