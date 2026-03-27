package com.aiagent.service.cron;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 定时任务调度器 - 参考 OpenClaw Cron
 */
@Slf4j
@Component
public class CronScheduler {

    /**
     * 定时任务注册表
     */
    private final Map<String, CronTask> tasks = new ConcurrentHashMap<>();

    /**
     * 任务执行计数器
     */
    private final Map<String, AtomicLong> executionCount = new ConcurrentHashMap<>();

    /**
     * 任务注册
     */
    public void register(CronTask task) {
        if (task == null) {
            throw new IllegalArgumentException("Task cannot be null");
        }

        String taskId = task.getId();
        tasks.put(taskId, task);
        executionCount.put(taskId, new AtomicLong(0));
        log.info("Cron task registered: {} (cron: {})", taskId, task.getCronExpression());
    }

    /**
     * 注销任务
     */
    public void unregister(String taskId) {
        tasks.remove(taskId);
        executionCount.remove(taskId);
        log.info("Cron task unregistered: {}", taskId);
    }

    /**
     * 执行指定任务
     */
    public void execute(String taskId) {
        CronTask task = tasks.get(taskId);
        if (task != null) {
            executeTask(task);
        } else {
            log.warn("Task not found: {}", taskId);
        }
    }

    /**
     * 执行所有任务
     */
    public void executeAll() {
        for (CronTask task : tasks.values()) {
            executeTask(task);
        }
    }

    private void executeTask(CronTask task) {
        String taskId = task.getId();
        long count = executionCount.get(taskId).incrementAndGet();

        log.info("Executing cron task: {} (execution #{})", taskId, count);

        try {
            task.execute();
            log.info("Cron task completed: {}", taskId);
        } catch (Exception e) {
            log.error("Cron task failed: {}", taskId, e);
        }
    }

    /**
     * 获取任务执行次数
     */
    public long getExecutionCount(String taskId) {
        AtomicLong counter = executionCount.get(taskId);
        return counter != null ? counter.get() : 0;
    }

    /**
     * 获取所有任务
     */
    public Map<String, CronTask> getAllTasks() {
        return new ConcurrentHashMap<>(tasks);
    }

    // ==================== 内置定时任务 ====================

    /**
     * 周期性记忆摘要任务 - 每小时执行一次
     * 实际使用 @Scheduled 简化实现
     */
    // @Scheduled(cron = "0 0 * * * *") // 每小时
    public void scheduledMemorySummary() {
        log.info("Scheduled memory summary triggered at {}", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        // 实际调用 MemoryService.triggerSummary() 对所有活跃会话
    }

    /**
     * 周期性 RAG 知识库更新 - 每天凌晨执行
     */
    // @Scheduled(cron = "0 0 2 * * *") // 每天凌晨2点
    public void scheduledRagUpdate() {
        log.info("Scheduled RAG update triggered at {}", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        // 实际调用 RAG 服务更新知识库
    }

    /**
     * 周期性健康检查 - 每5分钟执行
     */
    // @Scheduled(fixedRate = 300000) // 5分钟
    public void scheduledHealthCheck() {
        log.debug("Scheduled health check at {}", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    }
}
