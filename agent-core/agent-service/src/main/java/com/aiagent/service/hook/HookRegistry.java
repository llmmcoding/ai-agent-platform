package com.aiagent.service.hook;

import com.aiagent.common.dto.AgentRequest;
import com.aiagent.common.dto.AgentResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 钩子注册表 - 参考 OpenClaw HookRegistry
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
                log.debug("Executing hook: {} ({})", hook.getName(), type);
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

    /**
     * 执行预处理器
     */
    public boolean executePreProcess(AgentRequest request) {
        HookContext ctx = HookContext.forPreProcess(request);
        return executeHooks(Hook.HookType.PRE_PROCESS, ctx);
    }

    /**
     * 执行后处理器
     */
    public boolean executePostProcess(AgentRequest request, AgentResponse response) {
        HookContext ctx = HookContext.forPostProcess(request, response);
        return executeHooks(Hook.HookType.POST_PROCESS, ctx);
    }

    /**
     * 执行错误处理
     */
    public boolean executeErrorHandler(AgentRequest request, Throwable error) {
        HookContext ctx = HookContext.forError(request, error);
        return executeHooks(Hook.HookType.ERROR, ctx);
    }

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
}
