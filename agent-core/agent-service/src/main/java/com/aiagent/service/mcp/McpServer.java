package com.aiagent.service.mcp;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP Server - 参考 OpenClaw MCP 支持
 * 实现 Model Context Protocol 协议
 */
@Slf4j
@Component
public class McpServer {

    /**
     * MCP 工具注册表
     */
    private final Map<String, McpTool> tools = new ConcurrentHashMap<>();

    /**
     * MCP 资源注册表
     */
    private final Map<String, McpResource> resources = new ConcurrentHashMap<>();

    /**
     * 注册 MCP 工具
     */
    public void registerTool(McpTool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("Tool cannot be null");
        }

        String toolId = tool.getId();
        if (toolId == null || toolId.isEmpty()) {
            throw new IllegalArgumentException("Tool ID cannot be null or empty");
        }

        tools.put(toolId, tool);
        log.info("MCP tool registered: {}", toolId);
    }

    /**
     * 注销 MCP 工具
     */
    public void unregisterTool(String toolId) {
        tools.remove(toolId);
        log.info("MCP tool unregistered: {}", toolId);
    }

    /**
     * 获取 MCP 工具
     */
    public McpTool getTool(String toolId) {
        return tools.get(toolId);
    }

    /**
     * 获取所有 MCP 工具
     */
    public Collection<McpTool> getAllTools() {
        return tools.values();
    }

    /**
     * 注册 MCP 资源
     */
    public void registerResource(McpResource resource) {
        if (resource == null) {
            throw new IllegalArgumentException("Resource cannot be null");
        }

        resources.put(resource.getUri(), resource);
        log.info("MCP resource registered: {}", resource.getUri());
    }

    /**
     * 获取 MCP 资源
     */
    public McpResource getResource(String uri) {
        return resources.get(uri);
    }

    /**
     * 获取所有 MCP 资源
     */
    public Collection<McpResource> getAllResources() {
        return resources.values();
    }

    /**
     * 执行 MCP 工具
     */
    public String executeTool(String toolId, Map<String, Object> arguments) throws McpException {
        McpTool tool = tools.get(toolId);
        if (tool == null) {
            throw new McpException("Tool not found: " + toolId);
        }

        log.debug("Executing MCP tool: {}", toolId);
        return tool.execute(arguments);
    }

    /**
     * 获取 MCP 服务器信息
     */
    public McpServerInfo getServerInfo() {
        return new McpServerInfo(
                "AI Agent MCP Server",
                "1.0.0",
                tools.size(),
                resources.size()
        );
    }

    /**
     * MCP 工具定义
     */
    @Data
    public static class McpTool {
        private String id;
        private String name;
        private String description;
        private List<McpParameter> inputSchema;

        public String execute(Map<String, Object> arguments) throws McpException {
            // 子类实现
            return "{}";
        }
    }

    /**
     * MCP 参数定义
     */
    @Data
    public static class McpParameter {
        private String name;
        private String type;
        private String description;
        private boolean required;
        private Object defaultValue;
    }

    /**
     * MCP 资源定义
     */
    @Data
    public static class McpResource {
        private String uri;
        private String name;
        private String description;
        private String mimeType;
    }

    /**
     * MCP 服务器信息
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class McpServerInfo {
        private String name;
        private String version;
        private int toolCount;
        private int resourceCount;
    }

    /**
     * MCP 异常
     */
    public static class McpException extends Exception {
        public McpException(String message) {
            super(message);
        }

        public McpException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
