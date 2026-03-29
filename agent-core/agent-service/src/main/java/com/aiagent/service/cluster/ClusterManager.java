package com.aiagent.service.cluster;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 集群管理器 - 参考 OpenClaw Agents 集群
 * 提供服务发现、负载均衡、健康检查功能
 */
@Slf4j
@Component
public class ClusterManager {

    private final DiscoveryClient discoveryClient;
    private final LoadBalancerClient loadBalancerClient;
    private final Map<String, Long> instanceLastCheck = new ConcurrentHashMap<>();
    private static final long HEALTH_CHECK_INTERVAL = 30000; // 30秒

    public ClusterManager(DiscoveryClient discoveryClient,
                          @Autowired(required = false) LoadBalancerClient loadBalancerClient) {
        this.discoveryClient = discoveryClient;
        this.loadBalancerClient = loadBalancerClient;
    }

    /**
     * 获取所有服务实例
     */
    public List<ServiceInstance> getInstances(String serviceId) {
        return discoveryClient.getInstances(serviceId);
    }

    /**
     * 获取所有服务名称
     */
    public List<String> getServices() {
        return discoveryClient.getServices();
    }

    /**
     * 根据负载均衡选择实例
     */
    public ServiceInstance choose(String serviceId) {
        if (loadBalancerClient == null) {
            // 降级：返回第一个可用实例
            List<ServiceInstance> instances = getInstances(serviceId);
            return instances.isEmpty() ? null : instances.get(0);
        }
        return loadBalancerClient.choose(serviceId);
    }

    /**
     * 选择最健康的实例 (过滤不可用实例)
     */
    public ServiceInstance chooseHealthy(String serviceId) {
        List<ServiceInstance> instances = getInstances(serviceId);
        if (instances.isEmpty()) {
            return null;
        }

        // 过滤健康的实例
        List<ServiceInstance> healthyInstances = instances.stream()
                .filter(this::isHealthy)
                .collect(Collectors.toList());

        if (healthyInstances.isEmpty()) {
            log.warn("No healthy instances found for service: {}", serviceId);
            return instances.get(0); // 降级返回第一个
        }

        // 简单轮询负载均衡
        return healthyInstances.get((int) (System.currentTimeMillis() % healthyInstances.size()));
    }

    /**
     * 检查实例是否健康
     */
    private boolean isHealthy(ServiceInstance instance) {
        // 检查元数据中的健康状态
        Map<String, String> metadata = instance.getMetadata();
        String healthy = metadata.get("healthy");
        if ("false".equals(healthy)) {
            return false;
        }

        // 检查实例是否超时未响应
        String instanceId = instance.getInstanceId();
        if (instanceId != null) {
            Long lastCheck = instanceLastCheck.get(instanceId);
            if (lastCheck != null && System.currentTimeMillis() - lastCheck > HEALTH_CHECK_INTERVAL) {
                return false;
            }
        }

        return true;
    }

    /**
     * 更新实例检查时间
     */
    public void markChecked(String instanceId) {
        instanceLastCheck.put(instanceId, System.currentTimeMillis());
    }

    /**
     * 获取服务实例数量
     */
    public int getInstanceCount(String serviceId) {
        return getInstances(serviceId).size();
    }

    /**
     * 获取当前服务实例信息
     */
    public ServiceInstance getCurrentInstance(String serviceId) {
        List<ServiceInstance> instances = getInstances(serviceId);
        String currentHost = System.getenv("HOSTNAME") != null ? System.getenv("HOSTNAME")
                : System.getProperty("java.rmi.server.hostname", "localhost");

        return instances.stream()
                .filter(i -> i.getHost().equals(currentHost))
                .findFirst()
                .orElse(instances.isEmpty() ? null : instances.get(0));
    }

    /**
     * 检查服务是否可用
     */
    public boolean isServiceAvailable(String serviceId) {
        return !getInstances(serviceId).isEmpty();
    }

    /**
     * 获取服务描述信息
     */
    @Data
    public static class ServiceInfo {
        private String serviceId;
        private int instanceCount;
        private boolean available;
        private List<InstanceDetail> instances;

        @Data
        public static class InstanceDetail {
            private String instanceId;
            private String host;
            private int port;
            private boolean healthy;
        }
    }

    /**
     * 获取完整服务信息
     */
    public ServiceInfo getServiceInfo(String serviceId) {
        List<ServiceInstance> instances = getInstances(serviceId);
        ServiceInfo info = new ServiceInfo();
        info.setServiceId(serviceId);
        info.setInstanceCount(instances.size());
        info.setAvailable(!instances.isEmpty());
        info.setInstances(instances.stream()
                .map(i -> {
                    ServiceInfo.InstanceDetail detail = new ServiceInfo.InstanceDetail();
                    detail.setInstanceId(i.getInstanceId());
                    detail.setHost(i.getHost());
                    detail.setPort(i.getPort());
                    detail.setHealthy(isHealthy(i));
                    return detail;
                })
                .collect(Collectors.toList()));
        return info;
    }
}
