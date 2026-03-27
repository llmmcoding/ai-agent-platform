package com.aiagent.service.cron;

/**
 * 定时任务接口
 */
public interface CronTask {

    /**
     * 获取任务 ID
     */
    String getId();

    /**
     * 获取任务名称
     */
    String getName();

    /**
     * 获取 Cron 表达式
     */
    String getCronExpression();

    /**
     * 执行任务
     */
    void execute() throws CronTaskException;

    /**
     * 是否启用
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * 任务异常
     */
    class CronTaskException extends Exception {
        public CronTaskException(String message) {
            super(message);
        }

        public CronTaskException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
