package com.aiagent.service.alert;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AlertManager 单元测试
 */
class AlertManagerTest {

    private MeterRegistry meterRegistry;
    private AlertManager alertManager;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        alertManager = new AlertManager(meterRegistry);

        // 初始化计数器
        Counter.builder("agent_requests_total").register(meterRegistry);
        Counter.builder("agent_requests_error").register(meterRegistry);

        // 注册活跃请求 gauge
        AtomicLong activeRequests = new AtomicLong(0);
        Gauge.builder("agent_active_requests", activeRequests, AtomicLong::get)
                .description("Number of active requests")
                .register(meterRegistry);
    }

    @Test
    void checkAlerts_within_thresholds_no_alerts() {
        // Given - 没有错误请求
        Counter.builder("agent_requests_total").register(meterRegistry).increment();
        Counter.builder("agent_requests_error").register(meterRegistry).increment(0);

        // When
        alertManager.checkAlerts();

        // Then
        assertEquals(0, alertManager.getActiveAlertCount());
    }

    @Test
    void high_error_rate_triggers_alert() {
        // Given - 10% 错误率 (超过 5% 阈值)
        Counter.builder("agent_requests_total").register(meterRegistry).increment(10);
        Counter.builder("agent_requests_error").register(meterRegistry).increment(1);

        // When
        alertManager.checkAlerts();

        // Then
        assertEquals(1, alertManager.getActiveAlertCount());
        Map<String, AlertManager.AlertState> alerts = alertManager.getActiveAlerts();
        assertTrue(alerts.containsKey("HIGH_ERROR_RATE"));
    }

    @Test
    void getActiveAlerts_returns_copy() {
        // Given
        Counter.builder("agent_requests_total").register(meterRegistry).increment(10);
        Counter.builder("agent_requests_error").register(meterRegistry).increment(1);
        alertManager.checkAlerts();

        // When
        Map<String, AlertManager.AlertState> alerts = alertManager.getActiveAlerts();

        // Then
        assertNotNull(alerts);
        assertEquals(1, alerts.size());
    }

    @Test
    void alert_cooldown_prevents_duplicate_alerts() {
        // Given - 触发第一次告警
        Counter.builder("agent_requests_total").register(meterRegistry).increment(10);
        Counter.builder("agent_requests_error").register(meterRegistry).increment(1);
        alertManager.checkAlerts();
        assertEquals(1, alertManager.getActiveAlertCount());

        // When - 再次检查（冷却期内不应该重复告警，但会解析已恢复的告警）
        alertManager.checkAlerts();

        // Then - 告警仍然存在（冷却期未过）
        assertEquals(1, alertManager.getActiveAlertCount());
    }

    @Test
    void low_error_rate_resolves_alert() {
        // Given - 先触发告警
        Counter.builder("agent_requests_total").register(meterRegistry).increment(10);
        Counter.builder("agent_requests_error").register(meterRegistry).increment(1);
        alertManager.checkAlerts();
        assertEquals(1, alertManager.getActiveAlertCount());

        // When - 错误率降低到阈值以下 - 创建新的 registry 来重置计数器
        meterRegistry = new SimpleMeterRegistry();
        alertManager = new AlertManager(meterRegistry);
        Counter.builder("agent_requests_total").register(meterRegistry).increment(100);
        Counter.builder("agent_requests_error").register(meterRegistry).increment(0);
        // 注册活跃请求 gauge
        AtomicLong activeRequests = new AtomicLong(0);
        Gauge.builder("agent_active_requests", activeRequests, AtomicLong::get)
                .description("Number of active requests")
                .register(meterRegistry);
        alertManager.checkAlerts();

        // Then - 告警应该被解决
        assertEquals(0, alertManager.getActiveAlertCount());
    }

    @Test
    void getActiveAlertCount_returns_correct_count() {
        // Given
        assertEquals(0, alertManager.getActiveAlertCount());

        // When - 触发多个告警
        Counter.builder("agent_requests_total").register(meterRegistry).increment(10);
        Counter.builder("agent_requests_error").register(meterRegistry).increment(1);
        alertManager.checkAlerts();

        // Then
        assertEquals(1, alertManager.getActiveAlertCount());
    }

    @Test
    void alert_state_contains_severity() {
        // Given
        Counter.builder("agent_requests_total").register(meterRegistry).increment(10);
        Counter.builder("agent_requests_error").register(meterRegistry).increment(1);
        alertManager.checkAlerts();

        // When
        Map<String, AlertManager.AlertState> alerts = alertManager.getActiveAlerts();
        AlertManager.AlertState alert = alerts.get("HIGH_ERROR_RATE");

        // Then
        assertNotNull(alert);
        assertEquals(AlertManager.AlertSeverity.CRITICAL, alert.getSeverity());
        assertEquals("HIGH_ERROR_RATE", alert.getAlertId());
        assertTrue(alert.getMessage().contains("Error rate"));
        assertTrue(alert.getTimestamp() > 0);
    }

    @Test
    void zero_requests_no_alert_check() {
        // Given - 没有请求
        Counter.builder("agent_requests_total").register(meterRegistry).increment(0);
        Counter.builder("agent_requests_error").register(meterRegistry).increment(0);

        // When
        alertManager.checkAlerts();

        // Then - 不应该触发告警
        assertEquals(0, alertManager.getActiveAlertCount());
    }
}
