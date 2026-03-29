package com.aiagent.service.alert;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 告警管理器 - 参考 OpenClaw infra 监控告警
 * 基于 Prometheus metrics 实现阈值告警
 */
@Slf4j
@Component
public class AlertManager {

    private final MeterRegistry meterRegistry;

    // 告警阈值配置
    private static final double ERROR_RATE_THRESHOLD = 0.05; // 5% 错误率
    private static final long LATENCY_P99_THRESHOLD_MS = 5000; // P99 延迟 5s
    private static final double CPU_USAGE_THRESHOLD = 0.8; // CPU 80%
    private static final double MEMORY_USAGE_THRESHOLD = 0.85; // 内存 85%
    private static final int ACTIVE_REQUEST_THRESHOLD = 100; // 活跃请求数

    // 告警状态
    private final Map<String, AlertState> activeAlerts = new ConcurrentHashMap<>();

    // 上次告警时间 (防止告警风暴)
    private long lastErrorAlertTime = 0;
    private long lastLatencyAlertTime = 0;
    private static final long ALERT_COOLDOWN_MS = 60000; // 1分钟冷却

    public AlertManager(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 定期检查告警条件
     */
    @Scheduled(fixedDelay = 30000) // 每30秒检查一次
    public void checkAlerts() {
        checkErrorRate();
        checkLatency();
        checkActiveRequests();
        cleanupOldAlerts();
    }

    /**
     * 检查错误率
     */
    private void checkErrorRate() {
        double errorCount = meterRegistry.counter("agent_requests_error").count();
        double totalCount = meterRegistry.counter("agent_requests_total").count();

        if (totalCount == 0) return;

        double errorRate = errorCount / totalCount;

        if (errorRate > ERROR_RATE_THRESHOLD) {
            long now = System.currentTimeMillis();
            if (now - lastErrorAlertTime > ALERT_COOLDOWN_MS) {
                fireAlert("HIGH_ERROR_RATE", AlertSeverity.CRITICAL,
                        String.format("Error rate %.2f%% exceeds threshold %.2f%%", errorRate * 100, ERROR_RATE_THRESHOLD * 100));
                lastErrorAlertTime = now;
            }
        } else {
            resolveAlert("HIGH_ERROR_RATE");
        }
    }

    /**
     * 检查延迟
     */
    private void checkLatency() {
        double p99Latency = getPercentileValue("agent_request_latency", 0.99);

        if (p99Latency > LATENCY_P99_THRESHOLD_MS) {
            long now = System.currentTimeMillis();
            if (now - lastLatencyAlertTime > ALERT_COOLDOWN_MS) {
                fireAlert("HIGH_LATENCY", AlertSeverity.WARNING,
                        String.format("P99 latency %.0fms exceeds threshold %dms", p99Latency, LATENCY_P99_THRESHOLD_MS));
                lastLatencyAlertTime = now;
            }
        } else {
            resolveAlert("HIGH_LATENCY");
        }
    }

    /**
     * 检查活跃请求数
     */
    private void checkActiveRequests() {
        Double activeRequests = meterRegistry.find("agent_active_requests").gauge().value();

        if (activeRequests != null && activeRequests > ACTIVE_REQUEST_THRESHOLD) {
            fireAlert("HIGH_ACTIVE_REQUESTS", AlertSeverity.WARNING,
                    String.format("Active requests %d exceeds threshold %d", activeRequests.intValue(), ACTIVE_REQUEST_THRESHOLD));
        } else {
            resolveAlert("HIGH_ACTIVE_REQUESTS");
        }
    }

    /**
     * 触发告警
     */
    private void fireAlert(String alertId, AlertSeverity severity, String message) {
        if (!activeAlerts.containsKey(alertId)) {
            AlertState alert = new AlertState(alertId, severity, message, System.currentTimeMillis());
            activeAlerts.put(alertId, alert);
            log.warn("[ALERT] {} - {}: {}", severity, alertId, message);
            sendAlertNotification(alert);
        }
    }

    /**
     * 解决告警
     */
    private void resolveAlert(String alertId) {
        if (activeAlerts.remove(alertId) != null) {
            log.info("[ALERT RESOLVED] {}", alertId);
        }
    }

    /**
     * 发送告警通知 (可扩展: 邮件、Slack、钉钉等)
     */
    private void sendAlertNotification(AlertState alert) {
        // 目前只记录日志，后续可扩展为:
        // - 发送邮件
        // - 发送 Slack 消息
        // - 发送钉钉 webhook
        // - 发送 Prometheus AlertManager
        log.info("Alert notification sent: {} - {}", alert.getAlertId(), alert.getMessage());
    }

    /**
     * 获取百分位值
     */
    private double getPercentileValue(String timerName, double percentile) {
        try {
            io.micrometer.core.instrument.Timer timer = meterRegistry.find(timerName).timer();
            if (timer == null) return 0;
            // percentile 参数: 0.5 = p50, 0.95 = p95, 0.99 = p99
            io.micrometer.core.instrument.distribution.ValueAtPercentile[] percentileValues =
                    timer.takeSnapshot().percentileValues();
            int index = (int) (percentile * 100);
            if (index >= 0 && index < percentileValues.length) {
                return percentileValues[index].value() / 1_000_000; // ns to ms
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 清理旧告警 (超过24小时的告警自动清除)
     */
    private void cleanupOldAlerts() {
        long cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000;
        activeAlerts.entrySet().removeIf(entry -> entry.getValue().getTimestamp() < cutoff);
    }

    /**
     * 获取所有活跃告警
     */
    public Map<String, AlertState> getActiveAlerts() {
        return new ConcurrentHashMap<>(activeAlerts);
    }

    /**
     * 获取告警数量
     */
    public int getActiveAlertCount() {
        return activeAlerts.size();
    }

    /**
     * 告警状态
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class AlertState {
        private String alertId;
        private AlertSeverity severity;
        private String message;
        private long timestamp;
    }

    /**
     * 告警严重级别
     */
    public enum AlertSeverity {
        INFO,
        WARNING,
        CRITICAL
    }
}
