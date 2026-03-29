package com.aiagent.service.usage;

import com.aiagent.common.dto.ApiUsageRecord;
import com.aiagent.common.dto.ApiUsageStatistics;

import java.time.LocalDateTime;

/**
 * API 使用量服务
 */
public interface ApiUsageService {

    /**
     * 记录 API 使用量
     */
    void recordUsage(Long tenantId, Long apiKeyId, int inputTokens, int outputTokens,
                     long latencyMs, boolean isError);

    /**
     * 查询使用量统计
     */
    ApiUsageStatistics getUsageStatistics(Long tenantId, Long apiKeyId,
                                         LocalDateTime start, LocalDateTime end);

    /**
     * 查询 Key 使用量历史
     */
    ApiUsageStatistics getKeyUsageStatistics(Long apiKeyId,
                                              LocalDateTime start, LocalDateTime end);

    /**
     * 获取当前小时的记录
     */
    ApiUsageRecord getCurrentHourRecord(Long tenantId, Long apiKeyId);
}
