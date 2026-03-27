package com.aiagent.service.hook.builtin;

import com.aiagent.common.dto.AgentRequest;
import com.aiagent.common.dto.AgentResponse;
import com.aiagent.service.hook.Hook;
import com.aiagent.service.hook.HookContext;
import lombok.extern.slf4j.Slf4j;

/**
 * 内置审计日志钩子 - 记录所有请求和响应
 */
@Slf4j
public class AuditLogHook implements Hook {

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
        }

        return true;
    }

    private String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }
}
