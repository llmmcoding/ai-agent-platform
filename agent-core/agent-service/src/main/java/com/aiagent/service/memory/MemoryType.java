package com.aiagent.service.memory;

/**
 * 记忆类型枚举
 */
public enum MemoryType {
    /**
     * 情景记忆 - 对话记录，历史保留
     * 例如: "用户上周说想减肥"
     */
    EPISODIC,

    /**
     * 事实记忆 - 实体知识，可更新
     * 例如: "用户名叫张三，在阿里工作"
     */
    FACTUAL,

    /**
     * 偏好记忆 - 用户偏好，高频读取
     * 例如: "用户喜欢中文界面"
     */
    PREFERENCE
}
