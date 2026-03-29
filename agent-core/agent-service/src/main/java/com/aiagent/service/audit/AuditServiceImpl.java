package com.aiagent.service.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计日志服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    @Async
    public void logEvent(AuditEventType eventType, Long tenantId, Long apiKeyId,
                         String actor, String actorIp, Map<String, Object> details) {
        try {
            String detailsJson = details != null ? objectMapper.writeValueAsString(details) : null;

            String sql = """
                INSERT INTO api_audit_log
                (event_type, tenant_id, api_key_id, actor, actor_ip, details, created_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, NOW())
                """;

            jdbcTemplate.update(sql,
                    eventType.name(),
                    tenantId,
                    apiKeyId,
                    actor,
                    actorIp,
                    detailsJson
            );

        } catch (Exception e) {
            log.error("Failed to log audit event: type={}, error={}", eventType, e.getMessage());
        }
    }

    @Override
    public void logEvent(AuditEventType eventType, Long tenantId, Long apiKeyId) {
        logEvent(eventType, tenantId, apiKeyId, null, null, null);
    }

    @Override
    public List<AuditLogEntry> queryLogs(Long tenantId, String eventType,
                                         LocalDateTime start, LocalDateTime end, int page, int size) {
        StringBuilder sql = new StringBuilder("SELECT * FROM api_audit_log WHERE 1=1");
        java.util.List<Object> params = new java.util.ArrayList<>();

        if (tenantId != null) {
            sql.append(" AND tenant_id = ?");
            params.add(tenantId);
        }

        if (eventType != null && !eventType.isEmpty()) {
            sql.append(" AND event_type = ?");
            params.add(eventType);
        }

        if (start != null) {
            sql.append(" AND created_at >= ?");
            params.add(Timestamp.valueOf(start));
        }

        if (end != null) {
            sql.append(" AND created_at <= ?");
            params.add(Timestamp.valueOf(end));
        }

        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        params.add(size);
        params.add(page * size);

        return jdbcTemplate.query(sql.toString(), this::mapRow, params.toArray());
    }

    @Override
    public AuditLogEntry getLogById(Long id) {
        String sql = "SELECT * FROM api_audit_log WHERE id = ?";
        List<AuditLogEntry> results = jdbcTemplate.query(sql, this::mapRow, id);
        return results.isEmpty() ? null : results.get(0);
    }

    private AuditLogEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> details = parseDetails(rs.getString("details"));

        return new AuditLogEntry(
                rs.getLong("id"),
                AuditEventType.valueOf(rs.getString("event_type")),
                rs.getLong("tenant_id"),
                rs.getLong("api_key_id"),
                rs.getString("actor"),
                rs.getString("actor_ip"),
                details,
                rs.getTimestamp("created_at") != null ?
                        rs.getTimestamp("created_at").toLocalDateTime() : null
        );
    }

    private Map<String, Object> parseDetails(String json) {
        if (json == null || json.isEmpty()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse audit details: {}", e.getMessage());
            return new HashMap<>();
        }
    }
}
