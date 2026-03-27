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
     * 钩子类型
     */
    enum HookType {
        /** 请求预处理 */
        PRE_PROCESS,
        /** 请求后处理 */
        POST_PROCESS,
        /** 错误处理 */
        ERROR,
        /** 工具执行前 */
        PRE_TOOL,
        /** 工具执行后 */
        POST_TOOL,
        /** LLM 调用前 */
        PRE_LLM,
        /** LLM 调用后 */
        POST_LLM,
        /** 记忆操作前 */
        PRE_MEMORY,
        /** 记忆操作后 */
        POST_MEMORY
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
