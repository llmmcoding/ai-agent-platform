package com.aiagent.service.hook;

/**
 * 钩子接口 - 参考 OpenClaw Hooks 架构
 */
public interface Hook {

    /**
     * 获取钩子名称
     */
    String getName();

    /**
     * 钩子类型
     */
    HookType getType();

    /**
     * 执行钩子
     *
     * @param context 钩子上下文
     * @return 是否继续执行，false 表示中断
     */
    boolean execute(HookContext context) throws HookException;

    /**
     * 钩子执行优先级 (越小越先执行)
     */
    default int getOrder() {
        return 100;
    }

    /**
     * 钩子类型 - 扩展全生命周期 (借鉴 OpenClaw)
     */
    enum HookType {
        // ==================== Gateway ====================
        /** Gateway 启动 */
        GATEWAY_START,
        /** Gateway 停止 */
        GATEWAY_STOP,

        // ==================== Session ====================
        /** Session 开始 */
        SESSION_START,
        /** Session 结束 */
        SESSION_END,

        // ==================== Request ====================
        /** 请求预处理 */
        PRE_PROCESS,
        /** 请求后处理 */
        POST_PROCESS,
        /** 错误处理 */
        ERROR,

        // ==================== Agent ====================
        /** Agent 启动前 */
        BEFORE_AGENT_START,
        /** Agent 结束后 */
        AGENT_END,

        // ==================== LLM ====================
        /** 模型解析前 (路由决策) */
        BEFORE_MODEL_RESOLVE,
        /** Prompt 构建前 */
        BEFORE_PROMPT_BUILD,
        /** LLM 输入 (请求发送前) */
        LLM_INPUT,
        /** LLM 输出 (响应接收后) */
        LLM_OUTPUT,
        /** LLM 调用前 (兼容旧版) */
        PRE_LLM,
        /** LLM 调用后 (兼容旧版) */
        POST_LLM,

        // ==================== Tool ====================
        /** 工具调用前 */
        BEFORE_TOOL_CALL,
        /** 工具调用后 */
        AFTER_TOOL_CALL,
        /** 工具执行前 (兼容旧版) */
        PRE_TOOL,
        /** 工具执行后 (兼容旧版) */
        POST_TOOL,

        // ==================== Subagent ====================
        /** Subagent 创建中 */
        SUBAGENT_SPAWNING,
        /** Subagent 已创建 */
        SUBAGENT_SPAWNED,
        /** Subagent 已结束 */
        SUBAGENT_ENDED,

        // ==================== Memory ====================
        /** 压缩前 */
        BEFORE_COMPACTION,
        /** 压缩后 */
        AFTER_COMPACTION,
        /** 记忆操作前 (兼容旧版) */
        PRE_MEMORY,
        /** 记忆操作后 (兼容旧版) */
        POST_MEMORY,

        // ==================== Task ====================
        /** 任务创建 */
        TASK_CREATED,
        /** 任务完成 */
        TASK_COMPLETED,

        // ==================== RAG ====================
        /** RAG 查询前 */
        BEFORE_RAG_QUERY,
        /** RAG 查询后 */
        AFTER_RAG_QUERY
    }

    /**
     * 钩子异常
     */
    class HookException extends Exception {
        public HookException(String message) {
            super(message);
        }

        public HookException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
