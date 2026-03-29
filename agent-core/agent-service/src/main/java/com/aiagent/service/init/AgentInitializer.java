package com.aiagent.service.init;

import com.aiagent.service.browser.BrowserPlugin;
import com.aiagent.service.hook.HookRegistry;
import com.aiagent.service.hook.Hook;
import com.aiagent.service.hook.HookContext;
import com.aiagent.service.hook.builtin.AuditLogHook;
import com.aiagent.service.hook.builtin.MetricsHook;
import com.aiagent.service.plugin.PluginRegistry;
import com.aiagent.service.plugin.builtin.WebSearchPlugin;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Agent 初始化器
 * 在应用启动后初始化内置插件和钩子
 */
@Slf4j
@Component
public class AgentInitializer {

    private final PluginRegistry pluginRegistry;
    private final HookRegistry hookRegistry;
    private final WebSearchPlugin webSearchPlugin;
    private final BrowserPlugin browserPlugin;
    private final AuditLogHook auditLogHook;
    private final MetricsHook metricsHook;

    public AgentInitializer(
            PluginRegistry pluginRegistry,
            HookRegistry hookRegistry,
            WebSearchPlugin webSearchPlugin,
            BrowserPlugin browserPlugin,
            AuditLogHook auditLogHook,
            MetricsHook metricsHook) {
        this.pluginRegistry = pluginRegistry;
        this.hookRegistry = hookRegistry;
        this.webSearchPlugin = webSearchPlugin;
        this.browserPlugin = browserPlugin;
        this.auditLogHook = auditLogHook;
        this.metricsHook = metricsHook;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        log.info("Initializing AI Agent Platform...");

        // 注册内置插件
        registerBuiltinPlugins();

        // 注册内置钩子
        registerBuiltinHooks();

        // 初始化所有插件
        pluginRegistry.initializeAll();

        log.info("AI Agent Platform initialized - Plugins: {}, Hooks: {}",
                pluginRegistry.size(), hookRegistry.size());
    }

    /**
     * 注册内置插件
     */
    private void registerBuiltinPlugins() {
        pluginRegistry.register(webSearchPlugin);
        pluginRegistry.register(browserPlugin);
        log.info("Builtin plugins registered: WebSearchPlugin, BrowserPlugin");
    }

    /**
     * 注册内置钩子
     */
    private void registerBuiltinHooks() {
        // 注册审计日志 Hook (POST_PROCESS)
        hookRegistry.register(auditLogHook);

        // 注册指标收集 Hook (多阶段)
        // 使用 lambda 为不同 HookType 注册相同的 MetricsHook 逻辑
        hookRegistry.register(new MultiTypeHook(metricsHook, Hook.HookType.SESSION_START));
        hookRegistry.register(new MultiTypeHook(metricsHook, Hook.HookType.SESSION_END));
        hookRegistry.register(new MultiTypeHook(metricsHook, Hook.HookType.BEFORE_TOOL_CALL));
        hookRegistry.register(new MultiTypeHook(metricsHook, Hook.HookType.BEFORE_AGENT_START));
        hookRegistry.register(new MultiTypeHook(metricsHook, Hook.HookType.AGENT_END));
        hookRegistry.register(new MultiTypeHook(metricsHook, Hook.HookType.BEFORE_COMPACTION));
        hookRegistry.register(new MultiTypeHook(metricsHook, Hook.HookType.AFTER_COMPACTION));
        hookRegistry.register(new MultiTypeHook(metricsHook, Hook.HookType.TASK_CREATED));
        hookRegistry.register(new MultiTypeHook(metricsHook, Hook.HookType.TASK_COMPLETED));

        // 输出 Hook 统计
        var stats = hookRegistry.getStatistics();
        long totalHooks = stats.values().stream().mapToInt(Integer::intValue).sum();
        log.info("Builtin hooks registered: {} hooks across {} types", totalHooks, stats.size());
    }

    /**
     * 多类型 Hook 包装器
     */
    private record MultiTypeHook(MetricsHook delegate, Hook.HookType type) implements Hook {
        @Override
        public String getName() {
            return delegate.getName() + "@" + type;
        }

        @Override
        public HookType getType() {
            return type;
        }

        @Override
        public boolean execute(HookContext context) throws HookException {
            return delegate.execute(context);
        }

        @Override
        public int getOrder() {
            return delegate.getOrder();
        }
    }
}
