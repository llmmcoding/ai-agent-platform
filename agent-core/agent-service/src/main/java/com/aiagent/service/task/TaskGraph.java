package com.aiagent.service.task;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 任务图 (DAG)
 * 管理会话中的所有任务及其依赖关系
 */
@Data
@Builder
public class TaskGraph {

    private String sessionId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Builder.Default
    private Map<String, Task> tasks = new HashMap<>();

    /**
     * 添加任务
     */
    public void addTask(Task task) {
        tasks.put(task.getId(), task);
        updatedAt = LocalDateTime.now();
    }

    /**
     * 获取任务
     */
    public Optional<Task> getTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    /**
     * 移除任务
     */
    public void removeTask(String taskId) {
        Task removed = tasks.remove(taskId);
        if (removed != null) {
            // 清理依赖关系
            tasks.values().forEach(t -> {
                t.removeBlockedBy(taskId);
                if (t.getBlocks() != null) {
                    t.getBlocks().remove(taskId);
                }
            });
        }
        updatedAt = LocalDateTime.now();
    }

    /**
     * 完成任务
     * 自动解除下游任务的阻塞
     */
    public void completeTask(String taskId, String result) {
        Task task = tasks.get(taskId);
        if (task == null) {
            return;
        }

        task.setStatus(Task.TaskStatus.COMPLETED);
        task.setResult(result);
        task.setCompletedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());

        // 解除下游任务的阻塞
        if (task.getBlocks() != null) {
            for (String downstreamId : task.getBlocks()) {
                Task downstream = tasks.get(downstreamId);
                if (downstream != null) {
                    downstream.removeBlockedBy(taskId);
                    downstream.setUpdatedAt(LocalDateTime.now());
                }
            }
        }

        updatedAt = LocalDateTime.now();
    }

    /**
     * 获取可执行任务 (所有依赖已完成)
     */
    public List<Task> getExecutableTasks() {
        return tasks.values().stream()
                .filter(Task::isExecutable)
                .sorted(Comparator.comparingInt(Task::getPriority).reversed()
                        .thenComparing(Task::getCreatedAt))
                .collect(Collectors.toList());
    }

    /**
     * 获取被阻塞的任务
     */
    public List<Task> getBlockedTasks() {
        return tasks.values().stream()
                .filter(Task::isBlocked)
                .collect(Collectors.toList());
    }

    /**
     * 获取进行中的任务
     */
    public List<Task> getInProgressTasks() {
        return tasks.values().stream()
                .filter(t -> t.getStatus() == Task.TaskStatus.IN_PROGRESS)
                .collect(Collectors.toList());
    }

    /**
     * 获取待处理任务数
     */
    public long getPendingCount() {
        return tasks.values().stream()
                .filter(t -> t.getStatus() == Task.TaskStatus.PENDING)
                .count();
    }

    /**
     * 获取已完成任务数
     */
    public long getCompletedCount() {
        return tasks.values().stream()
                .filter(t -> t.getStatus() == Task.TaskStatus.COMPLETED)
                .count();
    }

    /**
     * 检测循环依赖
     */
    public boolean hasCycle() {
        Set<String> visited = new HashSet<>();
        Set<String> recStack = new HashSet<>();

        for (String taskId : tasks.keySet()) {
            if (hasCycleUtil(taskId, visited, recStack)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCycleUtil(String taskId, Set<String> visited, Set<String> recStack) {
        visited.add(taskId);
        recStack.add(taskId);

        Task task = tasks.get(taskId);
        if (task != null && task.getBlockedBy() != null) {
            for (String depId : task.getBlockedBy()) {
                if (!visited.contains(depId)) {
                    if (hasCycleUtil(depId, visited, recStack)) {
                        return true;
                    }
                } else if (recStack.contains(depId)) {
                    return true;
                }
            }
        }

        recStack.remove(taskId);
        return false;
    }

    /**
     * 获取拓扑排序 (执行顺序)
     */
    public List<Task> getTopologicalOrder() {
        List<Task> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> tempMark = new HashSet<>();

        for (String taskId : tasks.keySet()) {
            if (!visited.contains(taskId)) {
                topologicalSortUtil(taskId, visited, tempMark, result);
            }
        }

        Collections.reverse(result);
        return result;
    }

    private void topologicalSortUtil(String taskId, Set<String> visited,
                                      Set<String> tempMark, List<Task> result) {
        if (tempMark.contains(taskId)) {
            throw new IllegalStateException("Cycle detected in task graph");
        }

        if (visited.contains(taskId)) {
            return;
        }

        tempMark.add(taskId);
        Task task = tasks.get(taskId);

        if (task != null && task.getBlockedBy() != null) {
            for (String depId : task.getBlockedBy()) {
                topologicalSortUtil(depId, visited, tempMark, result);
            }
        }

        tempMark.remove(taskId);
        visited.add(taskId);
        result.add(task);
    }

    /**
     * 获取统计信息
     */
    public TaskGraphStats getStats() {
        long total = tasks.size();
        long pending = getPendingCount();
        long inProgress = getInProgressTasks().size();
        long completed = getCompletedCount();
        long failed = tasks.values().stream()
                .filter(t -> t.getStatus() == Task.TaskStatus.FAILED)
                .count();
        long blocked = getBlockedTasks().size();

        return TaskGraphStats.builder()
                .totalTasks(total)
                .pendingTasks(pending)
                .inProgressTasks(inProgress)
                .completedTasks(completed)
                .failedTasks(failed)
                .blockedTasks(blocked)
                .completionRate(total > 0 ? (double) completed / total : 0.0)
                .build();
    }

    /**
     * 任务图统计
     */
    @Data
    @Builder
    public static class TaskGraphStats {
        private long totalTasks;
        private long pendingTasks;
        private long inProgressTasks;
        private long completedTasks;
        private long failedTasks;
        private long blockedTasks;
        private double completionRate;
    }
}
