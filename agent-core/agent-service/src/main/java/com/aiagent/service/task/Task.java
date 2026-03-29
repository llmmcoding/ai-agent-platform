package com.aiagent.service.task;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务定义 (借鉴 learn-claude-code 的任务系统)
 *
 * 支持任务依赖图 (DAG)，依赖自动解析
 * 文件持久化到 .tasks/task_{id}.json
 */
@Data
@Builder
public class Task {

    private String id;
    private String subject;
    private String description;

    // 任务状态
    private TaskStatus status;

    // 依赖关系 (DAG)
    private List<String> blockedBy;  // 被哪些任务阻塞
    private List<String> blocks;     // 阻塞哪些任务

    // 所属会话/用户
    private String sessionId;
    private String userId;

    // 执行信息
    private String assignedTo;       // 分配给哪个 agent/subagent
    private String result;           // 执行结果

    // 时间戳
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime updatedAt;

    // 元数据
    private int priority;            // 优先级 (1-5, 5最高)
    private List<String> tags;
    private String parentTaskId;     // 父任务 (支持子任务)

    /**
     * 检查任务是否可执行 (所有依赖已完成)
     */
    public boolean isExecutable() {
        return status == TaskStatus.PENDING &&
               (blockedBy == null || blockedBy.isEmpty());
    }

    /**
     * 检查任务是否被阻塞
     */
    public boolean isBlocked() {
        return status == TaskStatus.PENDING &&
               blockedBy != null && !blockedBy.isEmpty();
    }

    /**
     * 添加阻塞任务
     */
    public void addBlockedBy(String taskId) {
        if (blockedBy == null) {
            blockedBy = new java.util.ArrayList<>();
        }
        if (!blockedBy.contains(taskId)) {
            blockedBy.add(taskId);
        }
    }

    /**
     * 移除阻塞任务
     */
    public void removeBlockedBy(String taskId) {
        if (blockedBy != null) {
            blockedBy.remove(taskId);
        }
    }

    /**
     * 添加下游任务
     */
    public void addBlocks(String taskId) {
        if (blocks == null) {
            blocks = new java.util.ArrayList<>();
        }
        if (!blocks.contains(taskId)) {
            blocks.add(taskId);
        }
    }

    /**
     * 任务状态枚举
     */
    public enum TaskStatus {
        PENDING,      // 等待执行
        IN_PROGRESS,  // 执行中
        COMPLETED,    // 已完成
        FAILED,       // 失败
        CANCELLED,    // 已取消
        BLOCKED       // 被阻塞 (计算状态)
    }
}
