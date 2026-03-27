package com.aiagent.service.plugin.builtin;

import com.aiagent.service.plugin.Plugin;
import lombok.extern.slf4j.Slf4j;

/**
 * 内置 Web Search 插件 - 封装 Python Worker 的 DuckDuckGo 搜索
 */
@Slf4j
public class WebSearchPlugin implements Plugin {

    private PluginContext context;

    @Override
    public String getId() {
        return "builtin.web_search";
    }

    @Override
    public String getName() {
        return "Web Search";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "Search the web for information using DuckDuckGo";
    }

    @Override
    public PluginType getType() {
        return PluginType.TOOL;
    }

    @Override
    public void initialize(PluginContext context) throws PluginException {
        this.context = context;
        log.info("WebSearchPlugin initialized");
    }

    @Override
    public String execute(String input) throws PluginException {
        if (context == null) {
            throw new PluginException("Plugin not initialized");
        }
        // 返回执行指令，实际执行由 ToolRegistry 路由到 Python Worker
        return "{\"tool\": \"web_search\", \"input\": " + input + "}";
    }

    @Override
    public void destroy() {
        log.info("WebSearchPlugin destroyed");
    }
}
