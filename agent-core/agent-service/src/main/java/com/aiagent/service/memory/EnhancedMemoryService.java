package com.aiagent.service.memory;

import java.util.List;
import java.util.Map;

/**
 * 增强记忆服务接口
 * 支持多级记忆: 情景记忆、事实记忆、偏好记忆
 */
public interface EnhancedMemoryService {

    // ==================== 存储操作 ====================

    /**
     * 存储记忆 (根据类型自动路由)
     */
    void save(MemoryEntry entry);

    /**
     * 批量存储记忆
     */
    void saveBatch(List<MemoryEntry> entries);

    // ==================== 检索操作 ====================

    /**
     * 按类型检索记忆
     *
     * @param userId 用户 ID
     * @param query  查询内容
     * @param type   记忆类型
     * @return 相关记忆列表
     */
    List<MemoryEntry> retrieve(String userId, String query, MemoryType type);

    /**
     * 检索所有类型的记忆
     */
    List<MemoryEntry> retrieveAll(String userId, String query);

    /**
     * 获取用户的特定类型所有记忆
     */
    List<MemoryEntry> getByType(String userId, MemoryType type);

    // ==================== 事实记忆操作 ====================

    /**
     * 更新事实记忆 (相同 entityKey 覆盖)
     */
    void updateFactual(String userId, String entityKey, String entityValue);

    /**
     * 获取事实记忆中的实体值
     */
    String getFactual(String userId, String entityKey);

    // ==================== 偏好记忆操作 ====================

    /**
     * 设置用户偏好
     */
    void setPreference(String userId, String key, Object value);

    /**
     * 获取用户偏好
     */
    Object getPreference(String userId, String key);

    /**
     * 获取用户所有偏好
     */
    Map<String, Object> getAllPreferences(String userId);

    // ==================== 管理操作 ====================

    /**
     * 删除记忆
     */
    void delete(String memoryId);

    /**
     * 清除用户的所有记忆
     */
    void clearUserMemory(String userId);

    /**
     * 获取用户记忆统计
     */
    Map<String, Integer> getMemoryStats(String userId);
}
