package com.aiagent.service.hook.builtin;

import com.aiagent.service.hook.Hook;
import com.aiagent.service.hook.HookContext;
import com.aiagent.service.metrics.AgentMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 指标收集 Hook - 展示全生命周期 Hook 使用
 * 在多个生命周期阶段收集指标
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricsHook implements Hook {

    private final AgentMetrics agentMetrics;

    // 会话级计数器
    private final AtomicLong sessionCounter = new AtomicLong(0);
    private final AtomicLong toolCallCounter = new AtomicLong(0);
    private final AtomicLong llmCallCounter = new AtomicLong(0);

    @PostConstruct
    public void init() {
        log.info("MetricsHook initialized - collecting metrics across all lifecycle stages");
    }

    @Override
    public String getName() {
        return "MetricsHook";
    }

    @Override
    public HookType getType() {
        // 这个 Hook 会在多个阶段注册，返回 null 表示需要在注册时指定
        return null;
    }

    @Override
    public boolean execute(HookContext context) throws HookException {
        String stage = context.getStage();

        switch (stage) {
            case "SESSION_START" -> {
                long count = sessionCounter.incrementAndGet();
                log.debug("Session started: {}, total active: {}", context.getSessionId(), count);
            }
            case "SESSION_END" -> {
                long count = sessionCounter.decrementAndGet();
                log.debug("Session ended: {}, remaining active: {}", context.getSessionId(), count);
            }
            case "BEFORE_TOOL_CALL" -> {
                long count = toolCallCounter.incrementAndGet();
                log.debug("Tool call: {}, count: {}", context.getToolName(), count);
            }
            case "BEFORE_AGENT_START" -> {
                log.debug("Agent execution started for request: {}",
                        context.getRequest() != null ? context.getRequest().getSessionId() : "unknown");
            }
            case "AGENT_END" -> {
                log.debug("Agent execution completed");
            }
            case "BEFORE_COMPACTION" -> {
                log.debug("Context compaction starting for session: {}", context.getSessionId());
            }
            case "AFTER_COMPACTION" -> {
                if (context.getCompactionResult() != null) {
                    log.debug("Context compaction completed: {} -> {} messages",
                            context.getCompactionResult().getOriginalCount(),
                            context.getCompactionResult().getCompactedCount());
                }
            }
            case "TASK_CREATED" -> {
                if (context.getTask() != null) {
                    log.debug("Task created: {} - {}",
                            context.getTask().getId(),
                            context.getTask().getSubject());
                }
            }
            case "TASK_COMPLETED" -> {
                if (context.getTask() != null) {
                    log.debug("Task completed: {} - {}",
                            context.getTask().getId(),
                            context.getTask().getSubject());
                }
            }
        }

        return true;
    }

    @Override
    public int getOrder() {
        return 50;  // 中等优先级，确保在其他 Hook 之后执行
    }
}
