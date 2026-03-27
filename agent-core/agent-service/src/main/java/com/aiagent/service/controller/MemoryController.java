package com.aiagent.service.controller;

import com.aiagent.common.Result;
import com.aiagent.service.memory.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 记忆管理 API
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/memory")
@Tag(name = "记忆管理", description = "多级记忆系统管理接口")
@RequiredArgsConstructor
public class MemoryController {

    private final EnhancedMemoryService memoryService;

    // ==================== 记忆存储 ====================

    @PostMapping("/save")
    @Operation(summary = "保存记忆", description = "保存单条记忆，自动判断类型")
    public Result<String> save(@RequestBody MemorySaveRequest request) {
        try {
            MemoryEntry entry = MemoryEntry.builder()
                    .type(request.getType())
                    .userId(request.getUserId())
                    .sessionId(request.getSessionId())
                    .content(request.getContent())
                    .entityKey(request.getEntityKey())
                    .entityValue(request.getEntityValue())
                    .metadata(request.getMetadata())
                    .importance(request.getImportance() > 0 ? request.getImportance() : 0.5)
                    .build();

            memoryService.save(entry);
            return Result.success("Memory saved");
        } catch (Exception e) {
            log.error("Failed to save memory: {}", e.getMessage(), e);
            return Result.error("Failed to save memory: " + e.getMessage());
        }
    }

    @PostMapping("/save/batch")
    @Operation(summary = "批量保存记忆")
    public Result<String> saveBatch(@RequestBody List<MemorySaveRequest> requests) {
        try {
            List<MemoryEntry> entries = requests.stream()
                    .map(r -> MemoryEntry.builder()
                            .type(r.getType())
                            .userId(r.getUserId())
                            .sessionId(r.getSessionId())
                            .content(r.getContent())
                            .importance(r.getImportance() > 0 ? r.getImportance() : 0.5)
                            .build())
                    .toList();

            memoryService.saveBatch(entries);
            return Result.success("Batch saved: " + entries.size());
        } catch (Exception e) {
            log.error("Failed to save batch: {}", e.getMessage(), e);
            return Result.error("Failed to save batch: " + e.getMessage());
        }
    }

    // ==================== 记忆检索 ====================

    @GetMapping("/retrieve")
    @Operation(summary = "检索记忆", description = "按类型检索用户记忆")
    public Result<List<MemoryEntry>> retrieve(
            @RequestParam String userId,
            @RequestParam String query,
            @RequestParam(defaultValue = "EPISODIC") MemoryType type) {

        try {
            List<MemoryEntry> results = memoryService.retrieve(userId, query, type);
            return Result.success(results);
        } catch (Exception e) {
            log.error("Failed to retrieve memories: {}", e.getMessage(), e);
            return Result.error("Failed to retrieve: " + e.getMessage());
        }
    }

    @GetMapping("/retrieve/all")
    @Operation(summary = "检索所有类型记忆")
    public Result<List<MemoryEntry>> retrieveAll(
            @RequestParam String userId,
            @RequestParam String query) {

        try {
            List<MemoryEntry> results = memoryService.retrieveAll(userId, query);
            return Result.success(results);
        } catch (Exception e) {
            log.error("Failed to retrieve all memories: {}", e.getMessage(), e);
            return Result.error("Failed to retrieve: " + e.getMessage());
        }
    }

    // ==================== 事实记忆 ====================

    @PostMapping("/factual")
    @Operation(summary = "更新事实记忆")
    public Result<String> updateFactual(
            @RequestParam String userId,
            @RequestParam String entityKey,
            @RequestParam String entityValue) {

        try {
            memoryService.updateFactual(userId, entityKey, entityValue);
            return Result.success("Factual memory updated");
        } catch (Exception e) {
            log.error("Failed to update factual: {}", e.getMessage(), e);
            return Result.error("Failed to update: " + e.getMessage());
        }
    }

    @GetMapping("/factual")
    @Operation(summary = "获取事实记忆")
    public Result<String> getFactual(
            @RequestParam String userId,
            @RequestParam String entityKey) {

        try {
            String value = memoryService.getFactual(userId, entityKey);
            return Result.success(value != null ? value : "");
        } catch (Exception e) {
            log.error("Failed to get factual: {}", e.getMessage(), e);
            return Result.error("Failed to get: " + e.getMessage());
        }
    }

    // ==================== 偏好记忆 ====================

    @PostMapping("/preference/{userId}")
    @Operation(summary = "设置用户偏好")
    public Result<String> setPreference(
            @PathVariable String userId,
            @RequestParam String key,
            @RequestParam Object value) {

        try {
            memoryService.setPreference(userId, key, value);
            return Result.success("Preference set");
        } catch (Exception e) {
            log.error("Failed to set preference: {}", e.getMessage(), e);
            return Result.error("Failed to set: " + e.getMessage());
        }
    }

    @GetMapping("/preference/{userId}")
    @Operation(summary = "获取用户偏好")
    public Result<Map<String, Object>> getPreferences(@PathVariable String userId) {
        try {
            Map<String, Object> prefs = memoryService.getAllPreferences(userId);
            return Result.success(prefs);
        } catch (Exception e) {
            log.error("Failed to get preferences: {}", e.getMessage(), e);
            return Result.error("Failed to get: " + e.getMessage());
        }
    }

    @GetMapping("/preference/{userId}/{key}")
    @Operation(summary = "获取单个偏好")
    public Result<Object> getPreference(
            @PathVariable String userId,
            @PathVariable String key) {

        try {
            Object value = memoryService.getPreference(userId, key);
            return Result.success(value != null ? value : "");
        } catch (Exception e) {
            log.error("Failed to get preference: {}", e.getMessage(), e);
            return Result.error("Failed to get: " + e.getMessage());
        }
    }

    // ==================== 管理操作 ====================

    @DeleteMapping("/clear/{userId}")
    @Operation(summary = "清除用户记忆")
    public Result<String> clearUserMemory(@PathVariable String userId) {
        try {
            memoryService.clearUserMemory(userId);
            return Result.success("User memory cleared");
        } catch (Exception e) {
            log.error("Failed to clear memory: {}", e.getMessage(), e);
            return Result.error("Failed to clear: " + e.getMessage());
        }
    }

    @GetMapping("/stats/{userId}")
    @Operation(summary = "获取记忆统计")
    public Result<Map<String, Integer>> getStats(@PathVariable String userId) {
        try {
            Map<String, Integer> stats = memoryService.getMemoryStats(userId);
            return Result.success(stats);
        } catch (Exception e) {
            log.error("Failed to get stats: {}", e.getMessage(), e);
            return Result.error("Failed to get: " + e.getMessage());
        }
    }

    // ==================== 请求 DTO ====================

    @lombok.Data
    public static class MemorySaveRequest {
        private MemoryType type;
        private String userId;
        private String sessionId;
        private String content;
        private String entityKey;
        private String entityValue;
        private Map<String, Object> metadata;
        private double importance;
    }
}
