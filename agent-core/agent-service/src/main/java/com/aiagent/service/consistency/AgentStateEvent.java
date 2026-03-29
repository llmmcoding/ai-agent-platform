package com.aiagent.service.consistency;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Agent 状态变更事件
 * 用于事件溯源 (Event Sourcing)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStateEvent {
    /**
     * 事件 ID (UUID)
     */
    private String eventId;

    /**
     * Agent ID
     */
    private String agentId;

    /**
     * 会话 ID
     */
    private String sessionId;

    /**
     * 事件类型
     */
    private String eventType;

    /**
     * 事件负载
     */
    private Object payload;

    /**
     * 时间戳
     */
    private Instant timestamp;

    /**
     * Trace ID (用于链路追踪)
     */
    private String traceId;

    /**
     * 版本号 (用于乐观锁)
     */
    private long version;

    /**
     * 事件类型枚举
     */
    public enum EventType {
        // 会话事件
        SESSION_CREATED,
        SESSION_ENDED,

        // 消息事件
        MESSAGE_RECEIVED,
        MESSAGE_SENT,
        MESSAGE_ACKNOWLEDGED,

        // 工具事件
        TOOL_EXECUTION_STARTED,
        TOOL_EXECUTION_COMPLETED,
        TOOL_EXECUTION_FAILED,

        // 状态事件
        STATE_CHANGED,
        STATE_RESTORED,

        // 记忆事件
        MEMORY_UPDATED,
        MEMORY_RETRIEVED,

        // RAG 事件
        RAG_RETRIEVED,
        RAG_UPDATED
    }
}
