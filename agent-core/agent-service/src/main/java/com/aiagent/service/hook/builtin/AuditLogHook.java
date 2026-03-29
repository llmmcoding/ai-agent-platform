package com.aiagent.service.hook.builtin;

import com.aiagent.common.dto.AgentRequest;
import com.aiagent.common.dto.AgentResponse;
import com.aiagent.service.audit.AuditEventType;
import com.aiagent.service.audit.AuditService;
import com.aiagent.service.hook.Hook;
import com.aiagent.service.hook.HookContext;
import com.aiagent.service.tenant.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 内置审计日志钩子 - 记录所有请求和响应
 */
@Slf4j
@Component
public class AuditLogHook implements Hook {

    private final AuditService auditService;

    public AuditLogHook(@Autowired(required = false) AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public String getName() {
        return "audit_log";
    }

    @Override
    public HookType getType() {
        return HookType.POST_PROCESS;
    }

    @Override
    public int getOrder() {
        return 1; // 最先执行
    }

    @Override
    public boolean execute(HookContext context) throws HookException {
        AgentRequest request = context.getRequest();
        AgentResponse response = context.getResponse();

        if (request != null) {
            log.info("[AUDIT] sessionId={}, userId={}, userInput={}, status={}, latencyMs={}",
                    request.getSessionId(),
                    request.getUserId(),
                    truncate(request.getUserInput(), 100),
                    response != null ? response.getStatus() : "null",
                    response != null ? response.getLatencyMs() : 0);

            // Persist to database if AuditService is available
            if (auditService != null) {
                try {
                    var tenantContext = TenantContextHolder.getContext();
                    Long tenantId = tenantContext != null ? tenantContext.getTenantId() : null;
                    Long apiKeyId = tenantContext != null ? tenantContext.getApiKeyId() : null;
                    String userId = tenantContext != null ? tenantContext.getUserId() : request.getUserId();
                    String clientIp = context.getAttribute("clientIp") != null ? context.getAttribute("clientIp").toString() : null;

                    // Extract token usage from response
                    int inputTokens = 0;
                    int outputTokens = 0;
                    if (response != null && response.getTokenUsage() != null) {
                        inputTokens = response.getTokenUsage().getPromptTokens() != null ? response.getTokenUsage().getPromptTokens() : 0;
                        outputTokens = response.getTokenUsage().getCompletionTokens() != null ? response.getTokenUsage().getCompletionTokens() : 0;
                    }

                    auditService.logEvent(
                            AuditEventType.API_REQUEST_COMPLETED,
                            tenantId,
                            apiKeyId,
                            userId,
                            clientIp,
                            Map.of(
                                    "sessionId", request.getSessionId() != null ? request.getSessionId() : "",
                                    "userInput", truncate(request.getUserInput(), 100),
                                    "status", response != null ? response.getStatus() : "null",
                                    "latencyMs", response != null ? response.getLatencyMs() : 0,
                                    "inputTokens", inputTokens,
                                    "outputTokens", outputTokens
                            )
                    );
                } catch (Exception e) {
                    log.warn("Failed to persist audit log: {}", e.getMessage());
                }
            }
        }

        return true;
    }

    private String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }
}
