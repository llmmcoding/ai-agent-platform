package com.aiagent.service.cluster;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 健康检查器 - 定期检查服务实例健康状态
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HealthChecker {

    private final DiscoveryClient discoveryClient;
    private final ClusterManager clusterManager;

    /**
     * 实例健康状态缓存
     */
    private final Map<String, AtomicInteger> failureCount = new ConcurrentHashMap<>();
    private static final int FAILURE_THRESHOLD = 3; // 连续失败3次标记为不健康

    /**
     * 定期健康检查
     */
    @Scheduled(fixedDelay = 30000) // 每30秒检查一次
    public void checkHealth() {
        List<String> services = discoveryClient.getServices();
        for (String serviceId : services) {
            checkServiceHealth(serviceId);
        }
    }

    /**
     * 检查指定服务的所有实例健康状态
     */
    private void checkServiceHealth(String serviceId) {
        List<ServiceInstance> instances = discoveryClient.getInstances(serviceId);
        for (ServiceInstance instance : instances) {
            checkInstanceHealth(instance);
        }
    }

    /**
     * 检查单个实例健康状态
     */
    private void checkInstanceHealth(ServiceInstance instance) {
        String instanceId = instance.getInstanceId();
        String healthUrl = instance.getUri() + "/health";

        try {
            Boolean healthy = WebClient.builder()
                    .build()
                    .get()
                    .uri(healthUrl)
                    .retrieve()
                    .bodyToMono(Boolean.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();

            if (Boolean.TRUE.equals(healthy)) {
                failureCount.remove(instanceId);
                clusterManager.markChecked(instanceId);
                log.debug("Instance {} is healthy", instanceId);
            } else {
                handleFailure(instanceId, instance);
            }
        } catch (Exception e) {
            handleFailure(instanceId, instance);
        }
    }

    /**
     * 处理实例失败
     */
    private void handleFailure(String instanceId, ServiceInstance instance) {
        AtomicInteger failures = failureCount.computeIfAbsent(instanceId, k -> new AtomicInteger(0));
        int count = failures.incrementAndGet();

        if (count >= FAILURE_THRESHOLD) {
            log.warn("Instance {} marked as unhealthy after {} failures", instanceId, count);
            // 在实际生产中，这里应该通知注册中心标记实例为不健康
            // Nacos 会自动通过心跳机制处理不健康的实例
        }
    }

    /**
     * 获取实例连续失败次数
     */
    public int getFailureCount(String instanceId) {
        AtomicInteger failures = failureCount.get(instanceId);
        return failures != null ? failures.get() : 0;
    }

    /**
     * 重置实例失败计数
     */
    public void resetFailureCount(String instanceId) {
        failureCount.remove(instanceId);
    }
}
