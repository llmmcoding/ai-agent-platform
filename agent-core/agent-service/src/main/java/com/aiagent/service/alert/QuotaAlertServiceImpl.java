package com.aiagent.service.alert;

import com.aiagent.common.dto.QuotaAlert;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 配额告警服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaAlertServiceImpl implements QuotaAlertService {

    private final JdbcTemplate jdbcTemplate;

    @Value("${aiagent.quota.alert-threshold:0.8}")
    private double warningThreshold;

    @Override
    public void checkAndAlert(Long tenantId, Long apiKeyId, String limitType,
                             long limit, long current) {
        if (limit <= 0) return;

        double percent = (double) current / limit;

        // 100% exceeded
        if (percent >= 1.0) {
            createAlert(tenantId, apiKeyId, limitType + "_EXCEEDED", limit, current);
        }
        // 80% warning
        else if (percent >= warningThreshold) {
            createAlert(tenantId, apiKeyId, limitType + "_WARNING", limit, current);
        }
    }

    private void createAlert(Long tenantId, Long apiKeyId, String alertType,
                            long thresholdValue, long actualValue) {
        try {
            // 检查是否已存在未确认的同类告警
            String checkSql = """
                SELECT COUNT(*) FROM tenant_quota_alert
                WHERE tenant_id = ? AND alert_type = ? AND acknowledged = FALSE
                AND triggered_at > NOW() - INTERVAL '1 hour'
                """;

            Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class,
                    tenantId, alertType);

            if (count != null && count > 0) {
                // 已存在未确认的告警，跳过
                return;
            }

            String sql = """
                INSERT INTO tenant_quota_alert
                (tenant_id, api_key_id, alert_type, threshold_value, actual_value, triggered_at, acknowledged)
                VALUES (?, ?, ?, ?, ?, NOW(), FALSE)
                """;

            jdbcTemplate.update(sql, tenantId, apiKeyId, alertType, thresholdValue, actualValue);

            log.warn("Quota alert triggered: tenantId={}, type={}, threshold={}, actual={}",
                    tenantId, alertType, thresholdValue, actualValue);

        } catch (Exception e) {
            log.error("Failed to create alert: tenantId={}, error={}", tenantId, e.getMessage());
        }
    }

    @Override
    public List<QuotaAlert> getUnacknowledgedAlerts(Long tenantId) {
        String sql = """
            SELECT * FROM tenant_quota_alert
            WHERE tenant_id = ? AND acknowledged = FALSE
            ORDER BY triggered_at DESC
            """;
        return jdbcTemplate.query(sql, quotaAlertRowMapper(), tenantId);
    }

    @Override
    public List<QuotaAlert> getAlerts(Long tenantId, boolean acknowledgedOnly) {
        String sql = acknowledgedOnly
                ? "SELECT * FROM tenant_quota_alert WHERE tenant_id = ? AND acknowledged = TRUE ORDER BY triggered_at DESC"
                : "SELECT * FROM tenant_quota_alert WHERE tenant_id = ? ORDER BY triggered_at DESC";
        return jdbcTemplate.query(sql, quotaAlertRowMapper(), tenantId);
    }

    @Override
    public void acknowledgeAlert(Long alertId, String acknowledgedBy) {
        String sql = """
            UPDATE tenant_quota_alert
            SET acknowledged = TRUE, acknowledged_at = NOW(), acknowledged_by = ?
            WHERE id = ?
            """;
        jdbcTemplate.update(sql, acknowledgedBy, alertId);
    }

    @Override
    @Scheduled(cron = "0 0 3 * * ?")  // 每天凌晨 3 点清理
    public void cleanupOldAlerts() {
        try {
            String sql = """
                DELETE FROM tenant_quota_alert
                WHERE triggered_at < NOW() - INTERVAL '30 days'
                """;
            int deleted = jdbcTemplate.update(sql);
            if (deleted > 0) {
                log.info("Cleaned up {} old alerts", deleted);
            }
        } catch (Exception e) {
            log.error("Failed to cleanup old alerts: {}", e.getMessage());
        }
    }

    private RowMapper<QuotaAlert> quotaAlertRowMapper() {
        return (rs, rowNum) -> QuotaAlert.builder()
                .id(rs.getLong("id"))
                .tenantId(rs.getLong("tenant_id"))
                .apiKeyId(rs.getLong("api_key_id"))
                .alertType(rs.getString("alert_type"))
                .thresholdValue(rs.getLong("threshold_value"))
                .actualValue(rs.getLong("actual_value"))
                .triggeredAt(rs.getTimestamp("triggered_at") != null ?
                        rs.getTimestamp("triggered_at").toLocalDateTime() : null)
                .acknowledged(rs.getBoolean("acknowledged"))
                .acknowledgedAt(rs.getTimestamp("acknowledged_at") != null ?
                        rs.getTimestamp("acknowledged_at").toLocalDateTime() : null)
                .acknowledgedBy(rs.getString("acknowledged_by"))
                .build();
    }
}
