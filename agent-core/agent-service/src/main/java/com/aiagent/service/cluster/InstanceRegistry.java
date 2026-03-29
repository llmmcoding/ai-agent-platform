package com.aiagent.service.cluster;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.discovery.event.InstanceRegisteredEvent;
import org.springframework.cloud.client.serviceregistry.Registration;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 实例注册管理器
 * 处理服务实例的注册和注销事件
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InstanceRegistry {

    private final DiscoveryClient discoveryClient;
    private final ClusterManager clusterManager;

    private Registration registration;

    /**
     * 设置注册对象
     */
    public void setRegistration(Registration registration) {
        this.registration = registration;
    }

    /**
     * 实例注册事件监听
     */
    @EventListener
    public void onInstanceRegistered(InstanceRegisteredEvent event) {
        log.info("Instance registered event received: {}", event.getSource());
    }

    /**
     * 获取当前服务名称
     */
    public String getServiceId() {
        return registration != null ? registration.getServiceId() : null;
    }

    /**
     * 获取当前实例 ID
     */
    public String getInstanceId() {
        return registration != null ? registration.getInstanceId() : null;
    }

    /**
     * 获取当前实例 URI
     */
    public String getUri() {
        return registration != null ? registration.getUri().toString() : null;
    }

    /**
     * 获取所有同类服务实例
     */
    public List<ServiceInstance> getInstances() {
        String serviceId = getServiceId();
        if (serviceId == null) {
            return List.of();
        }
        return discoveryClient.getInstances(serviceId);
    }

    /**
     * 服务健康指示器
     */
    @Component("aiAgentHealthIndicator")
    public static class AiAgentHealthIndicator implements HealthIndicator {

        private final ClusterManager clusterManager;

        public AiAgentHealthIndicator(ClusterManager clusterManager) {
            this.clusterManager = clusterManager;
        }

        @Override
        public Health health() {
            String serviceId = "ai-agent-platform";
            if (clusterManager.isServiceAvailable(serviceId)) {
                return Health.up()
                        .withDetail("service", serviceId)
                        .withDetail("instanceCount", clusterManager.getInstanceCount(serviceId))
                        .build();
            } else {
                return Health.unknown()
                        .withDetail("service", serviceId)
                        .withDetail("message", "Service discovery not available")
                        .build();
            }
        }
    }
}
