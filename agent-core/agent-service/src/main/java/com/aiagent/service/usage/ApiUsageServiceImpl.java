package com.aiagent.service.usage;

import com.aiagent.common.dto.ApiUsageRecord;
import com.aiagent.common.dto.ApiUsageStatistics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * API 使用量服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiUsageServiceImpl implements ApiUsageService {

    private final JdbcTemplate jdbcTemplate;

    private static final DateTimeFormatter HOUR_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00:00");

    @Override
    public void recordUsage(Long tenantId, Long apiKeyId, int inputTokens, int outputTokens,
                           long latencyMs, boolean isError) {
        try {
            LocalDateTime hour = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0);
            String hourStr = hour.format(HOUR_FORMATTER);

            // Upsert usage record
            String sql = """
                INSERT INTO api_usage_record
                (tenant_id, api_key_id, record_hour, request_count, input_tokens, output_tokens,
                 total_tokens, error_count, total_latency_ms, created_at, updated_at)
                VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, NOW(), NOW())
                ON CONFLICT (tenant_id, api_key_id, record_hour)
                DO UPDATE SET
                    request_count = api_usage_record.request_count + 1,
                    input_tokens = api_usage_record.input_tokens + ?,
                    output_tokens = api_usage_record.output_tokens + ?,
                    total_tokens = api_usage_record.total_tokens + ?,
                    error_count = api_usage_record.error_count + ?,
                    total_latency_ms = api_usage_record.total_latency_ms + ?,
                    updated_at = NOW()
                """;

            jdbcTemplate.update(sql,
                    tenantId, apiKeyId, hourStr,
                    inputTokens, outputTokens, inputTokens + outputTokens, isError ? 1 : 0, latencyMs,
                    inputTokens, outputTokens, inputTokens + outputTokens, isError ? 1 : 0, latencyMs);

        } catch (Exception e) {
            log.error("Failed to record usage: tenantId={}, keyId={}, error={}",
                    tenantId, apiKeyId, e.getMessage());
        }
    }

    @Override
    public ApiUsageStatistics getUsageStatistics(Long tenantId, Long apiKeyId,
                                                 LocalDateTime start, LocalDateTime end) {
        String sql = """
            SELECT * FROM api_usage_record
            WHERE tenant_id = ? AND api_key_id = ?
            AND record_hour >= ? AND record_hour <= ?
            ORDER BY record_hour DESC
            """;

        List<ApiUsageRecord> records = jdbcTemplate.query(sql, usageRecordRowMapper(),
                tenantId, apiKeyId, start, end);

        return buildStatistics(tenantId, apiKeyId, start, end, records);
    }

    @Override
    public ApiUsageStatistics getKeyUsageStatistics(Long apiKeyId,
                                                    LocalDateTime start, LocalDateTime end) {
        String sql = """
            SELECT * FROM api_usage_record
            WHERE api_key_id = ?
            AND record_hour >= ? AND record_hour <= ?
            ORDER BY record_hour DESC
            """;

        List<ApiUsageRecord> records = jdbcTemplate.query(sql, usageRecordRowMapper(),
                apiKeyId, start, end);

        Long tenantId = records.isEmpty() ? null : records.get(0).getTenantId();
        return buildStatistics(tenantId, apiKeyId, start, end, records);
    }

    @Override
    public ApiUsageRecord getCurrentHourRecord(Long tenantId, Long apiKeyId) {
        LocalDateTime hour = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0);
        String hourStr = hour.format(HOUR_FORMATTER);

        String sql = """
            SELECT * FROM api_usage_record
            WHERE tenant_id = ? AND api_key_id = ? AND record_hour = ?
            """;

        List<ApiUsageRecord> records = jdbcTemplate.query(sql, usageRecordRowMapper(),
                tenantId, apiKeyId, hourStr);

        return records.isEmpty() ? null : records.get(0);
    }

    private ApiUsageStatistics buildStatistics(Long tenantId, Long apiKeyId,
                                                LocalDateTime start, LocalDateTime end,
                                                List<ApiUsageRecord> records) {
        long totalRequests = 0;
        long totalInputTokens = 0;
        long totalOutputTokens = 0;
        long totalErrors = 0;
        long totalLatency = 0;

        for (ApiUsageRecord record : records) {
            totalRequests += record.getRequestCount() != null ? record.getRequestCount() : 0;
            totalInputTokens += record.getInputTokens() != null ? record.getInputTokens() : 0;
            totalOutputTokens += record.getOutputTokens() != null ? record.getOutputTokens() : 0;
            totalErrors += record.getErrorCount() != null ? record.getErrorCount() : 0;
            totalLatency += record.getTotalLatencyMs() != null ? record.getTotalLatencyMs() : 0;
        }

        long avgLatency = totalRequests > 0 ? totalLatency / totalRequests : 0;

        return ApiUsageStatistics.builder()
                .tenantId(tenantId)
                .apiKeyId(apiKeyId)
                .startTime(start)
                .endTime(end)
                .totalRequests(totalRequests)
                .totalInputTokens(totalInputTokens)
                .totalOutputTokens(totalOutputTokens)
                .totalTokens(totalInputTokens + totalOutputTokens)
                .totalErrors(totalErrors)
                .avgLatencyMs(avgLatency)
                .records(records)
                .build();
    }

    private RowMapper<ApiUsageRecord> usageRecordRowMapper() {
        return (rs, rowNum) -> {
            long totalLatency = rs.getLong("total_latency_ms");
            long requestCount = rs.getLong("request_count");
            long avgLatency = requestCount > 0 ? totalLatency / requestCount : 0;

            return ApiUsageRecord.builder()
                    .id(rs.getLong("id"))
                    .tenantId(rs.getLong("tenant_id"))
                    .apiKeyId(rs.getLong("api_key_id"))
                    .recordHour(rs.getTimestamp("record_hour") != null ?
                            rs.getTimestamp("record_hour").toLocalDateTime() : null)
                    .requestCount(requestCount)
                    .inputTokens(rs.getLong("input_tokens"))
                    .outputTokens(rs.getLong("output_tokens"))
                    .totalTokens(rs.getLong("total_tokens"))
                    .errorCount(rs.getLong("error_count"))
                    .totalLatencyMs(totalLatency)
                    .avgLatencyMs(avgLatency)
                    .build();
        };
    }
}
