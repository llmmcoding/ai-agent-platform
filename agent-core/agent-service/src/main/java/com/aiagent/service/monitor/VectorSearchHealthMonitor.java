package com.aiagent.service.monitor;

import com.aiagent.service.metrics.AgentMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 向量搜索健康监控
 * 监控向量库可用性、超时、错误，并触发降级告警
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorSearchHealthMonitor {

    private final AgentMetrics agentMetrics;

    @Value("${aiagent.health-check.enabled:true}")
    private boolean healthCheckEnabled;

    // 每个 provider 的健康状态
    private final Map<String, ProviderHealth> providerHealthMap = new ConcurrentHashMap<>();

    // 降级触发统计
    private final AtomicInteger totalDegradeCount = new AtomicInteger(0);
    private final AtomicInteger primaryTimeoutCount = new AtomicInteger(0);
    private final AtomicInteger secondaryTimeoutCount = new AtomicInteger(0);
    private final AtomicInteger keywordFallbackCount = new AtomicInteger(0);

    // 滑动窗口时间窗口 (分钟)
    private static final int WINDOW_MINUTES = 5;

    @PostConstruct
    public void init() {
        log.info("VectorSearchHealthMonitor initialized, enabled={}", healthCheckEnabled);
    }

    /**
     * 记录成功
     */
    public void recordSuccess(String provider, long latencyMs) {
        ProviderHealth health = getOrCreateHealth(provider);
        health.recordSuccess(latencyMs);

        if (agentMetrics != null) {
            agentMetrics.recordVectorSearchLatency(provider, latencyMs / 1000.0);
        }
    }

    /**
     * 记录超时
     */
    public void recordTimeout(String provider) {
        ProviderHealth health = getOrCreateHealth(provider);
        health.recordTimeout();

        if ("milvus".equals(provider) || "pgvector".equals(provider)) {
            if (provider.equals(getPrimaryProvider())) {
                primaryTimeoutCount.incrementAndGet();
            } else {
                secondaryTimeoutCount.incrementAndGet();
            }
        }

        checkAndAlert(health, provider);
    }

    /**
     * 记录失败
     */
    public void recordFailure(String provider, Exception e) {
        ProviderHealth health = getOrCreateHealth(provider);
        health.recordFailure(e.getMessage());

        log.error("Vector search failure for provider {}: {}", provider, e.getMessage());

        checkAndAlert(health, provider);
    }

    /**
     * 记录降级触发
     */
    public void recordDegradeTriggered(String reason) {
        totalDegradeCount.incrementAndGet();

        if (agentMetrics != null) {
            agentMetrics.incrementRAGDegradeTriggered();
        }

        switch (reason) {
            case "primary_timeout", "primary_error" -> primaryTimeoutCount.incrementAndGet();
            case "secondary_timeout", "secondary_error" -> secondaryTimeoutCount.incrementAndGet();
            case "keyword_fallback" -> keywordFallbackCount.incrementAndGet();
        }

        log.warn("RAG degrade triggered: reason={}, total={}, primary_timeout={}, secondary_timeout={}, keyword_fallback={}",
                reason,
                totalDegradeCount.get(),
                primaryTimeoutCount.get(),
                secondaryTimeoutCount.get(),
                keywordFallbackCount.get());
    }

    /**
     * 获取健康状态
     */
    public Map<String, Object> getHealthStatus() {
        Map<String, Object> status = new ConcurrentHashMap<>();

        for (Map.Entry<String, ProviderHealth> entry : providerHealthMap.entrySet()) {
            ProviderHealth health = entry.getValue();
            Map<String, Object> providerStatus = new ConcurrentHashMap<>();

            providerStatus.put("available", health.isAvailable());
            providerStatus.put("totalRequests", health.getTotalRequests());
            providerStatus.put("successRate", health.getSuccessRate());
            providerStatus.put("avgLatencyMs", health.getAvgLatencyMs());
            providerStatus.put("timeoutCount", health.getTimeoutCount());
            providerStatus.put("errorCount", health.getErrorCount());
            providerStatus.put("lastError", health.getLastError());
            providerStatus.put("lastSuccessTime", health.getLastSuccessTime());

            status.put(entry.getKey(), providerStatus);
        }

        status.put("totalDegradeCount", totalDegradeCount.get());
        status.put("primaryTimeoutCount", primaryTimeoutCount.get());
        status.put("secondaryTimeoutCount", secondaryTimeoutCount.get());
        status.put("keywordFallbackCount", keywordFallbackCount.get());

        return status;
    }

    /**
     * 检查是否需要告警
     */
    private void checkAndAlert(ProviderHealth health, String provider) {
        if (!healthCheckEnabled) {
            return;
        }

        // 连续失败超过阈值
        if (health.getConsecutiveFailures() >= 3) {
            log.error("ALERT: Vector search provider {} has {} consecutive failures!",
                    provider, health.getConsecutiveFailures());

            if (agentMetrics != null) {
                agentMetrics.incrementRAGRecallFailures();
            }
        }

        // 超时率过高 (> 50% in recent window)
        if (health.getTimeoutRate() > 0.5) {
            log.warn("ALERT: Vector search provider {} has high timeout rate: {}%",
                    provider, (int) (health.getTimeoutRate() * 100));
        }
    }

    /**
     * 获取或创建健康状态
     */
    private ProviderHealth getOrCreateHealth(String provider) {
        return providerHealthMap.computeIfAbsent(provider, k -> new ProviderHealth(k));
    }

    private String getPrimaryProvider() {
        // 从配置获取，当前简单返回
        return "milvus"; // 默认主 provider
    }

    /**
     * 定期重置统计 (每小时)
     */
    @Scheduled(fixedRate = 3600000)
    public void resetStats() {
        int degrades = totalDegradeCount.getAndSet(0);
        int primary = primaryTimeoutCount.getAndSet(0);
        int secondary = secondaryTimeoutCount.getAndSet(0);
        int keyword = keywordFallbackCount.getAndSet(0);

        if (degrades > 0) {
            log.info("Hourly stats reset: degrades={}, primary_timeouts={}, secondary_timeouts={}, keyword_fallbacks={}",
                    degrades, primary, secondary, keyword);
        }

        // 重置各 provider 的滑动窗口统计
        for (ProviderHealth health : providerHealthMap.values()) {
            health.resetWindow();
        }
    }

    /**
     * Provider 健康状态
     */
    private static class ProviderHealth {
        private final String provider;
        private final AtomicInteger totalRequests = new AtomicInteger(0);
        private final AtomicInteger successCount = new AtomicInteger(0);
        private final AtomicInteger timeoutCount = new AtomicInteger(0);
        private final AtomicInteger errorCount = new AtomicInteger(0);
        private final AtomicLong totalLatencyMs = new AtomicLong(0);
        private volatile String lastError;
        private volatile long lastSuccessTime;

        // 滑动窗口计数
        private volatile int windowSuccessCount = 0;
        private volatile int windowTimeoutCount = 0;
        private volatile int windowErrorCount = 0;
        private volatile int windowRequestCount = 0;
        private volatile long windowTotalLatency = 0;
        private volatile int consecutiveFailures = 0;

        private final Object windowLock = new Object();

        public ProviderHealth(String provider) {
            this.provider = provider;
        }

        public void recordSuccess(long latencyMs) {
            totalRequests.incrementAndGet();
            successCount.incrementAndGet();
            totalLatencyMs.addAndGet(latencyMs);
            lastSuccessTime = System.currentTimeMillis();
            consecutiveFailures = 0;

            synchronized (windowLock) {
                windowRequestCount++;
                windowSuccessCount++;
                windowTotalLatency += latencyMs;
            }
        }

        public void recordTimeout() {
            totalRequests.incrementAndGet();
            timeoutCount.incrementAndGet();
            consecutiveFailures++;

            synchronized (windowLock) {
                windowRequestCount++;
                windowTimeoutCount++;
            }
        }

        public void recordFailure(String error) {
            totalRequests.incrementAndGet();
            errorCount.incrementAndGet();
            lastError = error;
            consecutiveFailures++;

            synchronized (windowLock) {
                windowRequestCount++;
                windowErrorCount++;
            }
        }

        public boolean isAvailable() {
            return consecutiveFailures < 3 && getTimeoutRate() < 0.5;
        }

        public double getSuccessRate() {
            int total = totalRequests.get();
            return total > 0 ? (double) successCount.get() / total : 1.0;
        }

        public double getTimeoutRate() {
            int total = totalRequests.get();
            return total > 0 ? (double) timeoutCount.get() / total : 0.0;
        }

        public long getAvgLatencyMs() {
            int successes = successCount.get();
            return successes > 0 ? totalLatencyMs.get() / successes : 0;
        }

        public int getTotalRequests() {
            return totalRequests.get();
        }

        public int getTimeoutCount() {
            return timeoutCount.get();
        }

        public int getErrorCount() {
            return errorCount.get();
        }

        public String getLastError() {
            return lastError;
        }

        public long getLastSuccessTime() {
            return lastSuccessTime;
        }

        public int getConsecutiveFailures() {
            return consecutiveFailures;
        }

        public void resetWindow() {
            synchronized (windowLock) {
                windowRequestCount = 0;
                windowSuccessCount = 0;
                windowTimeoutCount = 0;
                windowErrorCount = 0;
                windowTotalLatency = 0;
            }
        }
    }
}
