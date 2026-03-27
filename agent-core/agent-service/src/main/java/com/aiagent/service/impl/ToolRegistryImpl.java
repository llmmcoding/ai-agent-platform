package com.aiagent.service.impl;

import com.aiagent.common.dto.AgentRequest;
import com.aiagent.common.Constants;
import com.aiagent.common.exception.AgentException;
import com.aiagent.common.exception.ErrorCode;
import com.aiagent.service.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 工具注册器实现
 * 支持 Java Tool 和 Python Tool (通过 Kafka/gRPC)
 */
@Slf4j
@Component
public class ToolRegistryImpl implements ToolRegistry {

    private final Map<String, ToolDefinition> toolRegistry = new HashMap<>();
    private final ObjectMapper objectMapper;

    @Value("${aiagent.tool.async-enabled:true}")
    private boolean asyncEnabled;

    @Value("${aiagent.tool.timeout:30}")
    private int toolTimeout;

    public ToolRegistryImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        initializeBuiltinTools();
    }

    private void initializeBuiltinTools() {
        // 注册内置 Java Tools

        // 计算器工具
        register("calculator", "Evaluate a mathematical expression", Constants.Tool.JAVA,
                (input, request) -> {
                    String expression = (String) input.get("expression");
                    if (expression == null || expression.isEmpty()) {
                        return CompletableFuture.completedFuture("Error: expression is required");
                    }
                    try {
                        // 安全计算：只允许基本数学运算
                        String sanitized = expression.replaceAll("[^0-9+\\-*/().%^]", "");
                        double result = evaluateExpression(sanitized);
                        return CompletableFuture.completedFuture(String.valueOf(result));
                    } catch (Exception e) {
                        return CompletableFuture.completedFuture("Error: " + e.getMessage());
                    }
                });

        // 获取时间工具
        register("get_time", "Get current date and time", Constants.Tool.JAVA,
                (input, request) -> {
                    java.time.LocalDateTime now = java.time.LocalDateTime.now();
                    String format = (String) input.getOrDefault("format", "yyyy-MM-dd HH:mm:ss");
                    java.time.format.DateTimeFormatter formatter =
                            java.time.format.DateTimeFormatter.ofPattern(format);
                    return CompletableFuture.completedFuture(now.format(formatter));
                });

        // 文本处理工具
        register("text_process", "Process and transform text", Constants.Tool.JAVA,
                (input, request) -> {
                    String text = (String) input.get("text");
                    String operation = (String) input.getOrDefault("operation", "uppercase");

                    if (text == null) {
                        return CompletableFuture.completedFuture("Error: text is required");
                    }

                    String result = switch (operation.toLowerCase()) {
                        case "uppercase" -> text.toUpperCase();
                        case "lowercase" -> text.toLowerCase();
                        case "trim" -> text.trim();
                        case "length" -> String.valueOf(text.length());
                        case "word_count" -> String.valueOf(text.split("\\s+").length);
                        default -> text;
                    };

                    return CompletableFuture.completedFuture(result);
                });

        // 随机数生成工具
        register("random", "Generate random numbers", Constants.Tool.JAVA,
                (input, request) -> {
                    int min = ((Number) input.getOrDefault("min", 0)).intValue();
                    int max = ((Number) input.getOrDefault("max", 100)).intValue();
                    int count = ((Number) input.getOrDefault("count", 1)).intValue();

                    if (count > 100) {
                        count = 100; // 限制最大数量
                    }

                    StringBuilder sb = new StringBuilder();
                    java.util.Random random = new java.util.Random();
                    for (int i = 0; i < count; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(min + random.nextInt(max - min + 1));
                    }

                    return CompletableFuture.completedFuture(sb.toString());
                });

        // 用户信息查询工具 (示例)
        register("get_user_info", "Get user information", Constants.Tool.JAVA,
                (input, request) -> {
                    String userId = request.getUserId();
                    if (userId == null) {
                        userId = (String) input.get("userId");
                    }

                    // 模拟返回用户信息
                    Map<String, Object> userInfo = Map.of(
                            "userId", userId != null ? userId : "anonymous",
                            "status", "active",
                            "tier", "premium"
                    );

                    try {
                        return CompletableFuture.completedFuture(
                                objectMapper.writeValueAsString(userInfo)
                        );
                    } catch (JsonProcessingException e) {
                        return CompletableFuture.completedFuture("Error: " + e.getMessage());
                    }
                });

        log.info("Initialized {} builtin Java tools", toolRegistry.size());
    }

    @Override
    public void register(String name, String description, String type, ToolExecutor executor) {
        ToolDefinition definition = new ToolDefinition(name, description, type, executor);
        toolRegistry.put(name, definition);
        log.info("Registered tool: {} (type: {})", name, type);
    }

    @Override
    public CompletableFuture<String> execute(String name, Map<String, Object> input, AgentRequest request) {
        ToolDefinition tool = toolRegistry.get(name);

        if (tool == null) {
            // 工具不存在，尝试通过 Kafka 发送到 Python Worker
            log.warn("Tool {} not found in Java registry, trying Python Worker", name);
            return executeViaPythonWorker(name, input, request);
        }

        log.debug("Executing Java tool: {}", name);
        try {
            return tool.getExecutor().execute(input, request);
        } catch (Exception e) {
            log.error("Tool execution failed: {}", name, e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 通过 Python Worker 执行工具 (使用 Kafka)
     */
    private CompletableFuture<String> executeViaPythonWorker(String name, Map<String, Object> input, AgentRequest request) {
        CompletableFuture<String> future = new CompletableFuture<>();

        try {
            // 使用 ToolKafkaClient 发送消息到 Python Worker
            toolKafkaClient.sendToolRequest(name, input, request.getTraceId());
            future.complete("{\"status\":\"submitted\",\"tool\":\"" + name + "\"}");
        } catch (Exception e) {
            log.error("Failed to execute tool via Python Worker: {}", name, e);
            future.completeExceptionally(e);
        }

        return future;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private ToolKafkaClient toolKafkaClient;

    @Override
    public String getToolDescription(String name) {
        ToolDefinition tool = toolRegistry.get(name);
        if (tool == null) {
            return null;
        }

        try {
            Map<String, Object> desc = Map.of(
                    "name", tool.getName(),
                    "description", tool.getDescription(),
                    "type", tool.getType()
            );
            return objectMapper.writeValueAsString(desc);
        } catch (JsonProcessingException e) {
            return "{\"name\":\"" + name + "\"}";
        }
    }

    @Override
    public String getAllToolsDescription() {
        StringBuilder sb = new StringBuilder();
        for (ToolDefinition tool : toolRegistry.values()) {
            try {
                Map<String, Object> desc = Map.of(
                        "name", tool.getName(),
                        "description", tool.getDescription(),
                        "type", tool.getType()
                );
                sb.append(objectMapper.writeValueAsString(desc)).append("\n");
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize tool description: {}", tool.getName());
            }
        }
        return sb.toString();
    }

    @Override
    public boolean hasTool(String name) {
        return toolRegistry.containsKey(name);
    }

    /**
     * 安全执行数学表达式
     */
    private double evaluateExpression(String expression) {
        try {
            Object result = new javax.script.ScriptEngineManager()
                    .getEngineByName("JavaScript")
                    .eval(expression);
            if (result instanceof Number) {
                return ((Number) result).doubleValue();
            }
            return Double.parseDouble(result.toString());
        } catch (Exception e) {
            throw new RuntimeException("Invalid expression: " + e.getMessage());
        }
    }

    /**
     * 工具定义
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    private static class ToolDefinition {
        private String name;
        private String description;
        private String type;
        private ToolExecutor executor;
    }
}
