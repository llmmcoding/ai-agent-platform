package com.aiagent.service.memory;

/**
 * 重要性评分计算器
 */
public class ImportanceCalculator {

    /**
     * 计算记忆重要性评分
     *
     * @param content 记忆内容
     * @param role    角色 (user/assistant)
     * @param type    记忆类型
     * @return 重要性评分 (0.0 - 1.0)
     */
    public static double calculate(String content, String role, MemoryType type) {
        double score = 0.5;  // 默认基础分

        // 1. 明确要求记住
        if (containsKeyword(content, "记住", "重要", "别忘了", "一定要")) {
            score = Math.max(score, 1.0);
        }

        // 2. 包含实体信息 (数字、日期、名称)
        if (containsEntities(content)) {
            score = Math.max(score, 0.8);
        }

        // 3. 类型决定基础分
        switch (type) {
            case FACTUAL:
                score = Math.max(score, 0.7);  // 事实记忆默认较高
                break;
            case PREFERENCE:
                score = Math.max(score, 0.8);  // 偏好记忆必须记住
                break;
            case EPISODIC:
                score = Math.max(score, 0.5);  // 情景记忆默认
                break;
        }

        // 4. 用户输入权重更高
        if ("user".equalsIgnoreCase(role)) {
            score = Math.min(1.0, score + 0.1);
        }

        return score;
    }

    /**
     * 检查是否包含关键词
     */
    private static boolean containsKeyword(String content, String... keywords) {
        if (content == null || keywords == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (content.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查是否包含实体信息
     */
    private static boolean containsEntities(String content) {
        if (content == null) {
            return false;
        }
        // 简单判断: 包含数字、日期格式、邮箱、URL 等
        return content.matches(".*\\d+.*") ||           // 数字
               content.contains("@") ||                  // 邮箱
               content.contains("http") ||                // URL
               content.contains("公司") ||                // 组织
               content.contains("我叫") ||                // 人名
               content.matches(".*\\d{4}[-/年].*");      // 日期
    }
}
