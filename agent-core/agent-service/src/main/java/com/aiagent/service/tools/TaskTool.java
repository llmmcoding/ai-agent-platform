package com.aiagent.service.tools;

import com.aiagent.common.dto.AgentRequest;
import com.aiagent.service.task.Task;
import com.aiagent.service.task.TaskGraph;
import com.aiagent.service.task.TaskManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Task Tool - 任务管理工具
 *
 * 供模型创建、管理、追踪任务，支持复杂工作流分解。
 * 借鉴 learn-claude-code 的任务系统设计。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskTool {

    private final TaskManager taskManager;

    /**
     * Tool 定义
     */
    public static final String TOOL_NAME = "task";
    public static final String TOOL_DESCRIPTION = """
        Manage tasks and workflows. Supports creating tasks, tracking dependencies,
        and managing task lifecycle. Use this to:
        - Break down complex work into smaller tasks
        - Track progress across multiple steps
        - Manage dependencies between tasks
        - Get the next task to work on

        Operations:
        - "create": Create a new task with optional dependencies
        - "complete": Mark a task as completed
        - "list": List all tasks for current session
        - "next": Get the next executable task (highest priority, unblocked)
        - "status": Get task status and dependency info
        - "add_dependency": Add a dependency between tasks
        """;

    /**
     * 执行工具
     */
    public CompletableFuture<String> execute(Map<String, Object> input, AgentRequest request) {
        String operation = (String) input.get("operation");
        String sessionId = request.getSessionId();

        if (operation == null) {
            return CompletableFuture.completedFuture("Error: operation is required");
        }

        try {
            return switch (operation) {
                case "create" -> createTask(input, sessionId);
                case "complete" -> completeTask(input, sessionId);
                case "list" -> listTasks(sessionId);
                case "next" -> getNextTask(sessionId);
                case "status" -> getTaskStatus(input, sessionId);
                case "add_dependency" -> addDependency(input, sessionId);
                default -> CompletableFuture.completedFuture("Error: unknown operation: " + operation);
            };
        } catch (Exception e) {
            log.error("TaskTool failed for operation: {}", operation, e);
            return CompletableFuture.completedFuture("Error: " + e.getMessage());
        }
    }

    /**
     * 创建任务
     */
    private CompletableFuture<String> createTask(Map<String, Object> input, String sessionId) {
        String subject = (String) input.get("subject");
        String description = (String) input.get("description");
        @SuppressWarnings("unchecked")
        List<String> blockedBy = (List<String>) input.get("blocked_by");
        @SuppressWarnings("unchecked")
        List<String> blocks = (List<String>) input.get("blocks");
        Integer priority = input.get("priority") != null ?
                ((Number) input.get("priority")).intValue() : 3;

        if (subject == null || subject.isEmpty()) {
            return CompletableFuture.completedFuture("Error: subject is required");
        }

        Task task = taskManager.createTask(sessionId, subject, description, blockedBy, blocks);
        task.setPriority(priority);
        taskManager.updateTask(task);

        String response = String.format("""
            Task created successfully.
            - ID: %s
            - Subject: %s
            - Status: %s
            - Dependencies: %s
            - Priority: %d
            """,
            task.getId(),
            task.getSubject(),
            task.getStatus(),
            task.getBlockedBy() != null && !task.getBlockedBy().isEmpty()
                ? String.join(", ", task.getBlockedBy())
                : "none",
            priority
        );

        return CompletableFuture.completedFuture(response);
    }

    /**
     * 完成任务
     */
    private CompletableFuture<String> completeTask(Map<String, Object> input, String sessionId) {
        String taskId = (String) input.get("task_id");
        String result = (String) input.get("result");

        if (taskId == null || taskId.isEmpty()) {
            return CompletableFuture.completedFuture("Error: task_id is required");
        }

        Optional<Task> taskOpt = taskManager.getTask(taskId);
        if (taskOpt.isEmpty()) {
            return CompletableFuture.completedFuture("Error: task not found: " + taskId);
        }

        taskManager.completeTask(taskId, result != null ? result : "Completed");

        // 检查是否有下游任务被解锁
        Task task = taskOpt.get();
        List<String> unblockedTasks = task.getBlocks() != null ?
                task.getBlocks().stream()
                    .filter(id -> taskManager.getTask(id).map(Task::isExecutable).orElse(false))
                    .collect(Collectors.toList())
                : List.of();

        String response;
        if (unblockedTasks.isEmpty()) {
            response = String.format("Task %s completed successfully.", taskId);
        } else {
            response = String.format("""
                Task %s completed successfully.
                Unblocked %d task(s): %s
                Use operation="next" to get the next task to work on.
                """,
                taskId,
                unblockedTasks.size(),
                String.join(", ", unblockedTasks)
            );
        }

        return CompletableFuture.completedFuture(response);
    }

    /**
     * 列出所有任务
     */
    private CompletableFuture<String> listTasks(String sessionId) {
        TaskGraph graph = taskManager.getTaskGraph(sessionId);
        List<Task> tasks = graph.getTopologicalOrder();

        if (tasks.isEmpty()) {
            return CompletableFuture.completedFuture("No tasks found for this session.");
        }

        TaskGraph.TaskGraphStats stats = graph.getStats();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Tasks for session: %s\n", sessionId));
        sb.append(String.format("Progress: %d/%d completed (%.1f%%)\n\n",
            stats.getCompletedTasks(),
            stats.getTotalTasks(),
            stats.getCompletionRate() * 100));

        sb.append("| ID | Subject | Status | Priority | Blocked By |\n");
        sb.append("|----|---------|--------|----------|------------|\n");

        for (Task task : tasks) {
            String blockedBy = task.getBlockedBy() != null && !task.getBlockedBy().isEmpty()
                ? String.join(", ", task.getBlockedBy())
                : "-";
            sb.append(String.format("| %s | %s | %s | %d | %s |\n",
                task.getId(),
                truncate(task.getSubject(), 20),
                task.getStatus(),
                task.getPriority(),
                blockedBy
            ));
        }

        return CompletableFuture.completedFuture(sb.toString());
    }

    /**
     * 获取下一个可执行任务
     */
    private CompletableFuture<String> getNextTask(String sessionId) {
        Optional<Task> taskOpt = taskManager.getNextExecutableTask(sessionId);

        if (taskOpt.isEmpty()) {
            TaskGraph graph = taskManager.getTaskGraph(sessionId);
            long pending = graph.getPendingCount();

            if (pending == 0) {
                return CompletableFuture.completedFuture(
                    "No pending tasks. All tasks are completed or no tasks exist."
                );
            } else {
                List<Task> blocked = graph.getBlockedTasks();
                return CompletableFuture.completedFuture(String.format(
                    """
                    No executable tasks available.
                    %d task(s) are pending but blocked by dependencies.
                    Blocked tasks: %s
                    Complete dependency tasks to unblock them.
                    """,
                    blocked.size(),
                    blocked.stream().map(Task::getId).collect(Collectors.joining(", "))
                ));
            }
        }

        Task task = taskOpt.get();
        String response = String.format("""
            Next task to work on:
            - ID: %s
            - Subject: %s
            - Description: %s
            - Priority: %d
            - Created: %s

            To complete this task, use:
            operation="complete", task_id="%s", result="<your result>"
            """,
            task.getId(),
            task.getSubject(),
            task.getDescription() != null ? task.getDescription() : "N/A",
            task.getPriority(),
            task.getCreatedAt(),
            task.getId()
        );

        return CompletableFuture.completedFuture(response);
    }

    /**
     * 获取任务状态
     */
    private CompletableFuture<String> getTaskStatus(Map<String, Object> input, String sessionId) {
        String taskId = (String) input.get("task_id");

        if (taskId == null || taskId.isEmpty()) {
            return CompletableFuture.completedFuture("Error: task_id is required");
        }

        Optional<Task> taskOpt = taskManager.getTask(taskId);
        if (taskOpt.isEmpty()) {
            return CompletableFuture.completedFuture("Error: task not found: " + taskId);
        }

        Task task = taskOpt.get();
        String response = String.format("""
            Task Status:
            - ID: %s
            - Subject: %s
            - Status: %s
            - Priority: %d
            - Created: %s
            - Started: %s
            - Completed: %s
            - Blocked By: %s
            - Blocks: %s
            - Result: %s
            """,
            task.getId(),
            task.getSubject(),
            task.getStatus(),
            task.getPriority(),
            task.getCreatedAt(),
            task.getStartedAt() != null ? task.getStartedAt() : "N/A",
            task.getCompletedAt() != null ? task.getCompletedAt() : "N/A",
            task.getBlockedBy() != null && !task.getBlockedBy().isEmpty()
                ? String.join(", ", task.getBlockedBy())
                : "none",
            task.getBlocks() != null && !task.getBlocks().isEmpty()
                ? String.join(", ", task.getBlocks())
                : "none",
            task.getResult() != null ? task.getResult() : "N/A"
        );

        return CompletableFuture.completedFuture(response);
    }

    /**
     * 添加依赖
     */
    private CompletableFuture<String> addDependency(Map<String, Object> input, String sessionId) {
        String taskId = (String) input.get("task_id");
        String dependsOn = (String) input.get("depends_on");

        if (taskId == null || dependsOn == null) {
            return CompletableFuture.completedFuture("Error: task_id and depends_on are required");
        }

        taskManager.addDependency(taskId, dependsOn);

        return CompletableFuture.completedFuture(String.format(
            "Added dependency: task %s now depends on %s",
            taskId, dependsOn
        ));
    }

    /**
     * 截断字符串
     */
    private String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) {
            return s != null ? s : "";
        }
        return s.substring(0, maxLen - 3) + "...";
    }
}
