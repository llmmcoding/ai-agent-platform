package com.aiagent.service.controller;

import com.aiagent.common.Result;
import com.aiagent.service.cluster.ClusterManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 集群管理 API
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/cluster")
@RequiredArgsConstructor
public class ClusterController {

    private final ClusterManager clusterManager;

    /**
     * 获取所有服务列表
     */
    @GetMapping("/services")
    public Result<List<String>> getServices() {
        return Result.success(clusterManager.getServices());
    }

    /**
     * 获取指定服务的实例列表
     */
    @GetMapping("/services/{serviceId}")
    public Result<ClusterManager.ServiceInfo> getServiceInfo(@PathVariable String serviceId) {
        return Result.success(clusterManager.getServiceInfo(serviceId));
    }

    /**
     * 根据负载均衡选择实例
     */
    @GetMapping("/choose/{serviceId}")
    public Result<Object> chooseInstance(@PathVariable String serviceId) {
        var instance = clusterManager.choose(serviceId);
        if (instance == null) {
            return Result.error("No available instance");
        }
        return Result.success(Map.of(
                "host", instance.getHost(),
                "port", instance.getPort(),
                "instanceId", instance.getInstanceId()
        ));
    }

    /**
     * 获取当前服务实例信息
     */
    @GetMapping("/current")
    public Result<Object> getCurrentInstance() {
        var instance = clusterManager.getCurrentInstance("ai-agent-platform");
        if (instance == null) {
            return Result.error("Current instance not found");
        }
        return Result.success(Map.of(
                "host", instance.getHost(),
                "port", instance.getPort(),
                "instanceId", instance.getInstanceId(),
                "serviceId", instance.getServiceId()
        ));
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        String serviceId = "ai-agent-platform";
        boolean available = clusterManager.isServiceAvailable(serviceId);
        int count = clusterManager.getInstanceCount(serviceId);

        return Result.success(Map.of(
                "status", available ? "UP" : "DOWN",
                "service", serviceId,
                "instanceCount", count
        ));
    }
}
