package com.aiagent.service.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 安全配置 - 参考 OpenClaw Security
 */
@Data
@Component
@ConfigurationProperties(prefix = "aiagent.security")
public class SecurityConfig {

    /**
     * 是否启用安全检查
     */
    private boolean enabled = true;

    /**
     * API Key 认证
     */
    private boolean apiKeyEnabled = true;

    /**
     * 允许的 API Key 列表
     */
    private Set<String> allowedApiKeys = new HashSet<>();

    /**
     * Tool 白名单
     */
    private Set<String> toolWhitelist = new HashSet<>();

    /**
     * Tool 黑名单
     */
    private Set<String> toolBlacklist = new HashSet<>();

    /**
     * 日志脱敏
     */
    private boolean logSanitizationEnabled = true;

    /**
     * 最大请求大小 (字节)
     */
    private int maxRequestSize = 10 * 1024 * 1024; // 10MB

    /**
     * 是否启用 Tool 权限控制
     */
    private boolean toolPermissionEnabled = true;

    /**
     * 危险 Tool 列表 (需要额外确认)
     */
    private Set<String> dangerousTools = new HashSet<>();

    /**
     * 检查 API Key 是否有效
     */
    public boolean isValidApiKey(String apiKey) {
        if (!apiKeyEnabled || allowedApiKeys.isEmpty()) {
            return true;
        }
        return allowedApiKeys.contains(apiKey);
    }

    /**
     * 检查 Tool 是否允许执行
     */
    public boolean isToolAllowed(String toolName) {
        if (!toolPermissionEnabled) {
            return true;
        }

        // 黑名单优先
        if (!toolBlacklist.isEmpty() && toolBlacklist.contains(toolName)) {
            return false;
        }

        // 白名单其次
        if (!toolWhitelist.isEmpty() && !toolWhitelist.contains(toolName)) {
            return false;
        }

        return true;
    }

    /**
     * 检查 Tool 是否危险
     */
    public boolean isDangerousTool(String toolName) {
        return dangerousTools.contains(toolName);
    }

    /**
     * 脱敏日志
     */
    public String sanitizeLog(String content) {
        if (!logSanitizationEnabled || content == null) {
            return content;
        }

        // 简单的脱敏处理 - 实际应更复杂
        return content
                .replaceAll("password[=:]\"([^\"]+)\"", "password=***")
                .replaceAll("api[_-]?key[=:]\"([^\"]+)\"", "api_key=***")
                .replaceAll("token[=:]\"([^\"]+)\"", "token=***")
                .replaceAll("secret[=:]\"([^\"]+)\"", "secret=***");
    }
}
