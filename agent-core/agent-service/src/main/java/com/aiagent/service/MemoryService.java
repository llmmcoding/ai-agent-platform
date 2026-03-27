package com.aiagent.service;

/**
 * 记忆服务接口
 */
public interface MemoryService {

    /**
     * 获取短期记忆
     *
     * @param sessionId 会话 ID
     * @return 记忆上下文
     */
    String getShortTermMemory(String sessionId);

    /**
     * 更新短期记忆
     *
     * @param sessionId 会话 ID
     * @param userInput 用户输入
     * @param agentOutput Agent 输出
     */
    void updateShortTermMemory(String sessionId, String userInput, String agentOutput);

    /**
     * 获取长期记忆
     *
     * @param userId 用户 ID
     * @param query  查询内容
     * @return 相关记忆
     */
    String getLongTermMemory(String userId, String query);

    /**
     * 保存长期记忆
     *
     * @param userId 用户 ID
     * @param content 记忆内容
     */
    void saveLongTermMemory(String userId, String content);

    /**
     * 触发记忆摘要
     *
     * @param sessionId 会话 ID
     */
    void triggerSummary(String sessionId);

    /**
     * 清除会话记忆
     *
     * @param sessionId 会话 ID
     */
    void clearMemory(String sessionId);
}
