package com.aiagent.service.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 任务管理器
 * 文件持久化到 .tasks/ 目录 (借鉴 learn-claude-code 的设计)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskManager {

    private final ObjectMapper objectMapper;

    @Value("${aiagent.task.dir:.tasks}")
    private String taskDir;

    private Path tasksPath;

    @PostConstruct
    public void init() {
        tasksPath = Paths.get(taskDir);
        try {
            if (!Files.exists(tasksPath)) {
                Files.createDirectories(tasksPath);
                log.info("Created tasks directory: {}", tasksPath);
            }
        } catch (IOException e) {
            log.error("Failed to create tasks directory: {}", tasksPath, e);
        }
    }

    /**
     * 创建任务
     */
    public Task createTask(String sessionId, String subject, String description) {
        return createTask(sessionId, subject, description, null, null);
    }

    /**
     * 创建任务 (带依赖)
     */
    public Task createTask(String sessionId, String subject, String description,
                          List<String> blockedBy, List<String> blocks) {
        String taskId = generateTaskId();

        Task task = Task.builder()
                .id(taskId)
                .subject(subject)
                .description(description)
                .status(Task.TaskStatus.PENDING)
                .sessionId(sessionId)
                .blockedBy(blockedBy != null ? new ArrayList<>(blockedBy) : new ArrayList<>())
                .blocks(blocks != null ? new ArrayList<>(blocks) : new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .priority(3)  // 默认优先级
                .build();

        saveTask(task);
        log.info("Created task: {} - {} for session: {}", taskId, subject, sessionId);

        return task;
    }

    /**
     * 获取任务
     */
    public Optional<Task> getTask(String taskId) {
        Path file = tasksPath.resolve("task_" + taskId + ".json");
        if (!Files.exists(file)) {
            return Optional.empty();
        }

        try {
            String json = Files.readString(file);
            return Optional.of(objectMapper.readValue(json, Task.class));
        } catch (IOException e) {
            log.error("Failed to read task: {}", taskId, e);
            return Optional.empty();
        }
    }

    /**
     * 更新任务
     */
    public void updateTask(Task task) {
        task.setUpdatedAt(LocalDateTime.now());
        saveTask(task);
    }

    /**
     * 删除任务
     */
    public void deleteTask(String taskId) {
        try {
            Path file = tasksPath.resolve("task_" + taskId + ".json");
            Files.deleteIfExists(file);
            log.info("Deleted task: {}", taskId);
        } catch (IOException e) {
            log.error("Failed to delete task: {}", taskId, e);
        }
    }

    /**
     * 开始任务
     */
    public void startTask(String taskId, String assignedTo) {
        getTask(taskId).ifPresent(task -> {
            task.setStatus(Task.TaskStatus.IN_PROGRESS);
            task.setAssignedTo(assignedTo);
            task.setStartedAt(LocalDateTime.now());
            updateTask(task);
            log.info("Started task: {} assigned to: {}", taskId, assignedTo);
        });
    }

    /**
     * 完成任务
     * 自动解除下游任务的阻塞
     */
    public void completeTask(String taskId, String result) {
        getTask(taskId).ifPresent(task -> {
            // 标记当前任务完成
            task.setStatus(Task.TaskStatus.COMPLETED);
            task.setResult(result);
            task.setCompletedAt(LocalDateTime.now());
            updateTask(task);

            // 解除下游任务的阻塞
            if (task.getBlocks() != null) {
                for (String downstreamId : task.getBlocks()) {
                    getTask(downstreamId).ifPresent(downstream -> {
                        downstream.removeBlockedBy(taskId);
                        updateTask(downstream);
                        log.debug("Unblocked task: {} (dependency {} completed)",
                                downstreamId, taskId);
                    });
                }
            }

            log.info("Completed task: {} - {}", taskId, result);
        });
    }

    /**
     * 失败任务
     */
    public void failTask(String taskId, String error) {
        getTask(taskId).ifPresent(task -> {
            task.setStatus(Task.TaskStatus.FAILED);
            task.setResult(error);
            updateTask(task);
            log.warn("Failed task: {} - {}", taskId, error);
        });
    }

    /**
     * 获取会话的所有任务
     */
    public List<Task> getSessionTasks(String sessionId) {
        try (Stream<Path> paths = Files.list(tasksPath)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".json"))
                    .map(this::readTaskFile)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .filter(t -> sessionId.equals(t.getSessionId()))
                    .sorted(Comparator.comparing(Task::getCreatedAt))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Failed to list tasks for session: {}", sessionId, e);
            return Collections.emptyList();
        }
    }

    /**
     * 获取会话的任务图
     */
    public TaskGraph getTaskGraph(String sessionId) {
        List<Task> tasks = getSessionTasks(sessionId);

        TaskGraph graph = TaskGraph.builder()
                .sessionId(sessionId)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        tasks.forEach(graph::addTask);

        return graph;
    }

    /**
     * 获取可执行任务
     */
    public List<Task> getExecutableTasks(String sessionId) {
        return getTaskGraph(sessionId).getExecutableTasks();
    }

    /**
     * 获取下一个可执行任务 (优先级最高)
     */
    public Optional<Task> getNextExecutableTask(String sessionId) {
        return getExecutableTasks(sessionId).stream()
                .max(Comparator.comparingInt(Task::getPriority)
                        .thenComparing(Task::getCreatedAt));
    }

    /**
     * 添加任务依赖
     */
    public void addDependency(String taskId, String dependsOnTaskId) {
        getTask(taskId).ifPresent(task -> {
            getTask(dependsOnTaskId).ifPresent(dependency -> {
                task.addBlockedBy(dependsOnTaskId);
                dependency.addBlocks(taskId);
                updateTask(task);
                updateTask(dependency);
                log.debug("Added dependency: {} depends on {}", taskId, dependsOnTaskId);
            });
        });
    }

    /**
     * 清理已完成的任务 (超过保留期的)
     */
    public void cleanupCompletedTasks(int retentionDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);

        try (Stream<Path> paths = Files.list(tasksPath)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".json"))
                    .map(this::readTaskFile)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .filter(t -> t.getStatus() == Task.TaskStatus.COMPLETED)
                    .filter(t -> t.getCompletedAt() != null && t.getCompletedAt().isBefore(cutoff))
                    .forEach(t -> deleteTask(t.getId()));
        } catch (IOException e) {
            log.error("Failed to cleanup tasks", e);
        }
    }

    /**
     * 生成任务ID
     */
    private String generateTaskId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 保存任务到文件
     */
    private void saveTask(Task task) {
        try {
            Path file = tasksPath.resolve("task_" + task.getId() + ".json");
            String json = objectMapper.writeValueAsString(task);
            Files.writeString(file, json);
        } catch (IOException e) {
            log.error("Failed to save task: {}", task.getId(), e);
        }
    }

    /**
     * 读取任务文件
     */
    private Optional<Task> readTaskFile(Path file) {
        try {
            String json = Files.readString(file);
            Task task = objectMapper.readValue(json, Task.class);
            return Optional.of(task);
        } catch (IOException e) {
            log.warn("Failed to read task file: {}", file, e);
            return Optional.empty();
        }
    }
}
