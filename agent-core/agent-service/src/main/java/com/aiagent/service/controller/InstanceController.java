package com.aiagent.service.controller;

import com.aiagent.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.web.bind.annotation.*;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 服务实例管理
 * 用于集群环境下的实例管理和健康检查
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/instances")
@Tag(name = "实例管理", description = "服务实例管理和健康检查")
@RequiredArgsConstructor
public class InstanceController {

    private final DiscoveryClient discoveryClient;

    @Value("${spring.application.name:ai-agent-platform}")
    private String applicationName;

    @Value("${server.port:8080}")
    private int serverPort;

    /**
     * 获取当前服务实例列表
     */
    @GetMapping("/list")
    @Operation(summary = "获取实例列表", description = "获取所有注册到 Nacos 的 agent-core 实例")
    public Result<List<Map<String, Object>>> listInstances() {
        try {
            List<ServiceInstance> instances = discoveryClient.getInstances(applicationName);

            List<Map<String, Object>> instanceList = instances.stream()
                    .map(instance -> {
                        Map<String, Object> info = new HashMap<>();
                        info.put("instanceId", instance.getInstanceId());
                        info.put("host", instance.getHost());
                        info.put("port", instance.getPort());
                        info.put("scheme", instance.getScheme());
                        info.put("metadata", instance.getMetadata());
                        info.put("healthy", instance.isHealthy());
                        info.put("enabled", instance.isEnabled());
                        return info;
                    })
                    .collect(Collectors.toList());

            log.debug("Found {} instances for service: {}", instanceList.size(), applicationName);

            return Result.success(instanceList);

        } catch (Exception e) {
            log.error("Failed to get instances: {}", e.getMessage(), e);
            return Result.error("Failed to get instances: " + e.getMessage());
        }
    }

    /**
     * 获取当前实例信息
     */
    @GetMapping("/current")
    @Operation(summary = "获取当前实例", description = "获取当前实例的详细信息")
    public Result<Map<String, Object>> getCurrentInstance() {
        try {
            Map<String, Object> info = new HashMap<>();
            info.put("serviceId", applicationName);
            info.put("host", InetAddress.getLocalHost().getHostAddress());
            info.put("port", serverPort);
            info.put("instanceId", applicationName + ":" + serverPort);
            info.put("cluster", "DEFAULT");

            return Result.success(info);

        } catch (Exception e) {
            log.error("Failed to get current instance: {}", e.getMessage(), e);
            return Result.error("Failed to get current instance: " + e.getMessage());
        }
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    @Operation(summary = "健康检查", description = "检查当前实例和集群健康状态")
    public Result<Map<String, Object>> healthCheck() {
        try {
            List<ServiceInstance> instances = discoveryClient.getInstances(applicationName);

            boolean hasHealthyInstance = instances.stream().anyMatch(ServiceInstance::isHealthy);

            Map<String, Object> health = new HashMap<>();
            health.put("status", "UP");
            health.put("currentInstance", Map.of(
                    "host", InetAddress.getLocalHost().getHostAddress(),
                    "port", serverPort
            ));
            health.put("totalInstances", instances.size());
            health.put("healthyInstances", instances.stream().filter(ServiceInstance::isHealthy).count());
            health.put("clusterAvailable", hasHealthyInstance);

            return Result.success(health);

        } catch (Exception e) {
            log.error("Health check failed: {}", e.getMessage(), e);

            Map<String, Object> health = new HashMap<>();
            health.put("status", "DOWN");
            health.put("error", e.getMessage());

            return Result.success(health);
        }
    }

    /**
     * 获取服务描述
     */
    @GetMapping("/services")
    @Operation(summary = "获取所有服务", description = "获取 Nacos 注册中心的所有服务")
    public Result<List<String>> getServices() {
        try {
            List<String> services = discoveryClient.getServices();
            return Result.success(services);
        } catch (Exception e) {
            log.error("Failed to get services: {}", e.getMessage(), e);
            return Result.error("Failed to get services: " + e.getMessage());
        }
    }
}
