package com.aiagent.service.hook;

import com.aiagent.common.dto.AgentRequest;
import com.aiagent.common.dto.AgentResponse;
import com.aiagent.service.memory.ContextCompactionService;
import com.aiagent.service.task.Task;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 钩子注册表 - 扩展全生命周期 (借鉴 OpenClaw)
 * 统一管理所有钩子的注册和执行
 */
@Slf4j
@Component
public class HookRegistry {

    /**
     * 按类型索引的钩子
     */
    private final Map<Hook.HookType, List<Hook>> hooksByType = new EnumMap<>(Hook.HookType.class);

    /**
     * 注册钩子
     */
    public void register(Hook hook) {
        if (hook == null) {
            throw new IllegalArgumentException("Hook cannot be null");
        }

        Hook.HookType type = hook.getType();
        hooksByType.computeIfAbsent(type, k -> new ArrayList<>()).add(hook);

        log.info("Hook registered: {} ({})", hook.getName(), type);
    }

    /**
     * 注销钩子
     */
    public void unregister(String hookName) {
        for (List<Hook> hooks : hooksByType.values()) {
            hooks.removeIf(h -> h.getName().equals(hookName));
        }
        log.info("Hook unregistered: {}", hookName);
    }

    /**
     * 获取指定类型的所有钩子 (按优先级排序)
     */
    public List<Hook> getHooks(Hook.HookType type) {
        List<Hook> hooks = hooksByType.getOrDefault(type, Collections.emptyList());
        return hooks.stream()
                .sorted(Comparator.comparingInt(Hook::getOrder))
                .collect(Collectors.toList());
    }

    /**
     * 执行指定类型的所有钩子
     *
     * @param type    钩子类型
     * @param context 上下文
     * @return 是否全部成功执行
     */
    public boolean executeHooks(Hook.HookType type, HookContext context) {
        List<Hook> hooks = getHooks(type);

        for (Hook hook : hooks) {
            try {
                log.debug("Executing hook: {} ({}, priority={})", hook.getName(), type, hook.getOrder());
                boolean result = hook.execute(context);
                if (!result) {
                    log.warn("Hook {} returned false, stopping execution", hook.getName());
                    return false;
                }
            } catch (Hook.HookException e) {
                log.error("Hook {} execution failed: {}", hook.getName(), e.getMessage());
                return false;
            }
        }

        return true;
    }

    // ==================== Gateway Hooks ====================

    public boolean executeGatewayStart() {
        return executeHooks(Hook.HookType.GATEWAY_START, HookContext.forGatewayStart());
    }

    public boolean executeGatewayStop() {
        return executeHooks(Hook.HookType.GATEWAY_STOP, HookContext.forGatewayStop());
    }

    // ==================== Session Hooks ====================

    public boolean executeSessionStart(String sessionId, AgentRequest request) {
        return executeHooks(Hook.HookType.SESSION_START, HookContext.forSessionStart(sessionId, request));
    }

    public boolean executeSessionEnd(String sessionId, AgentRequest request, AgentResponse response) {
        return executeHooks(Hook.HookType.SESSION_END, HookContext.forSessionEnd(sessionId, request, response));
    }

    // ==================== Request Hooks ====================

    public boolean executePreProcess(AgentRequest request) {
        return executeHooks(Hook.HookType.PRE_PROCESS, HookContext.forPreProcess(request));
    }

    public boolean executePostProcess(AgentRequest request, AgentResponse response) {
        return executeHooks(Hook.HookType.POST_PROCESS, HookContext.forPostProcess(request, response));
    }

    // ==================== Agent Hooks ====================

    public boolean executeBeforeAgentStart(AgentRequest request) {
        return executeHooks(Hook.HookType.BEFORE_AGENT_START, HookContext.forBeforeAgentStart(request));
    }

    public boolean executeAgentEnd(AgentRequest request, AgentResponse response) {
        return executeHooks(Hook.HookType.AGENT_END, HookContext.forAgentEnd(request, response));
    }

    // ==================== LLM Hooks ====================

    public boolean executeBeforeModelResolve(AgentRequest request, String modelProvider) {
        return executeHooks(Hook.HookType.BEFORE_MODEL_RESOLVE,
                HookContext.forBeforeModelResolve(request, modelProvider));
    }

    public boolean executeBeforePromptBuild(AgentRequest request, String prompt) {
        return executeHooks(Hook.HookType.BEFORE_PROMPT_BUILD,
                HookContext.forBeforePromptBuild(request, prompt));
    }

    public boolean executeLlmInput(AgentRequest request, String modelProvider, String requestBody) {
        return executeHooks(Hook.HookType.LLM_INPUT,
                HookContext.forLlmInput(request, modelProvider, requestBody));
    }

    public boolean executeLlmOutput(AgentRequest request, String modelProvider, String responseBody) {
        return executeHooks(Hook.HookType.LLM_OUTPUT,
                HookContext.forLlmOutput(request, modelProvider, responseBody));
    }

    // ==================== Tool Hooks ====================

    public boolean executeBeforeToolCall(AgentRequest request, String toolName, Map<String, Object> input) {
        return executeHooks(Hook.HookType.BEFORE_TOOL_CALL,
                HookContext.forBeforeToolCall(request, toolName, input));
    }

    public boolean executeAfterToolCall(AgentRequest request, String toolName,
                                         Map<String, Object> input, Object output) {
        return executeHooks(Hook.HookType.AFTER_TOOL_CALL,
                HookContext.forAfterToolCall(request, toolName, input, output));
    }

    // ==================== Subagent Hooks ====================

    public boolean executeSubagentSpawning(AgentRequest request, String subagentId) {
        return executeHooks(Hook.HookType.SUBAGENT_SPAWNING,
                HookContext.forSubagentSpawning(request, subagentId));
    }

    public boolean executeSubagentSpawned(AgentRequest request, String subagentId) {
        return executeHooks(Hook.HookType.SUBAGENT_SPAWNED,
                HookContext.forSubagentSpawned(request, subagentId));
    }

    public boolean executeSubagentEnded(AgentRequest request, String subagentId, AgentResponse response) {
        return executeHooks(Hook.HookType.SUBAGENT_ENDED,
                HookContext.forSubagentEnded(request, subagentId, response));
    }

    // ==================== Memory Hooks ====================

    public boolean executeBeforeCompaction(AgentRequest request, String sessionId) {
        return executeHooks(Hook.HookType.BEFORE_COMPACTION,
                HookContext.forBeforeCompaction(request, sessionId));
    }

    public boolean executeAfterCompaction(AgentRequest request, String sessionId,
                                          ContextCompactionService.CompactionResult result) {
        return executeHooks(Hook.HookType.AFTER_COMPACTION,
                HookContext.forAfterCompaction(request, sessionId, result));
    }

    // ==================== Task Hooks ====================

    public boolean executeTaskCreated(AgentRequest request, Task task) {
        return executeHooks(Hook.HookType.TASK_CREATED,
                HookContext.forTaskCreated(request, task));
    }

    public boolean executeTaskCompleted(AgentRequest request, Task task) {
        return executeHooks(Hook.HookType.TASK_COMPLETED,
                HookContext.forTaskCompleted(request, task));
    }

    // ==================== RAG Hooks ====================

    public boolean executeBeforeRagQuery(AgentRequest request, String query) {
        return executeHooks(Hook.HookType.BEFORE_RAG_QUERY,
                HookContext.forBeforeRagQuery(request, query));
    }

    public boolean executeAfterRagQuery(AgentRequest request, String query, String results) {
        return executeHooks(Hook.HookType.AFTER_RAG_QUERY,
                HookContext.forAfterRagQuery(request, query, results));
    }

    // ==================== Error Hooks ====================

    public boolean executeErrorHandler(AgentRequest request, Throwable error) {
        return executeHooks(Hook.HookType.ERROR, HookContext.forError(request, error));
    }

    // ==================== Legacy Hooks (兼容旧版) ====================

    public boolean executePreTool(AgentRequest request, String toolName, Map<String, Object> input) {
        return executeHooks(Hook.HookType.PRE_TOOL,
                HookContext.forBeforeToolCall(request, toolName, input));
    }

    public boolean executePostTool(AgentRequest request, String toolName,
                                    Map<String, Object> input, Object output) {
        return executeHooks(Hook.HookType.POST_TOOL,
                HookContext.forAfterToolCall(request, toolName, input, output));
    }

    public boolean executePreLlm(AgentRequest request, String modelProvider, String requestBody) {
        return executeHooks(Hook.HookType.PRE_LLM,
                HookContext.forLlmInput(request, modelProvider, requestBody));
    }

    public boolean executePostLlm(AgentRequest request, String modelProvider, String responseBody) {
        return executeHooks(Hook.HookType.POST_LLM,
                HookContext.forLlmOutput(request, modelProvider, responseBody));
    }

    // ==================== Statistics ====================

    /**
     * 获取已注册的钩子数量
     */
    public int size() {
        return hooksByType.values().stream().mapToInt(List::size).sum();
    }

    /**
     * 获取指定类型的钩子数量
     */
    public int sizeOfType(Hook.HookType type) {
        return hooksByType.getOrDefault(type, Collections.emptyList()).size();
    }

    /**
     * 获取所有注册钩子的统计信息
     */
    public Map<Hook.HookType, Integer> getStatistics() {
        Map<Hook.HookType, Integer> stats = new EnumMap<>(Hook.HookType.class);
        for (Hook.HookType type : Hook.HookType.values()) {
            stats.put(type, sizeOfType(type));
        }
        return stats;
    }
}
