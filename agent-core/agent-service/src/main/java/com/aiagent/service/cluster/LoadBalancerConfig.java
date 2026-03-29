package com.aiagent.service.cluster;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 负载均衡配置
 */
@Slf4j
@Configuration
public class LoadBalancerConfig {

    /**
     * 简单的负载均衡器实现
     * 使用 DiscoveryClient 和可选的 LoadBalancerClient
     */
    @Bean
    public SimpleLoadBalancer simpleLoadBalancer(DiscoveryClient discoveryClient,
                                                 @Autowired(required = false) LoadBalancerClient loadBalancerClient) {
        return new SimpleLoadBalancer(discoveryClient, loadBalancerClient);
    }

    /**
     * 简单负载均衡器
     */
    public static class SimpleLoadBalancer {
        private final DiscoveryClient discoveryClient;
        private final LoadBalancerClient loadBalancerClient;

        public SimpleLoadBalancer(DiscoveryClient discoveryClient, LoadBalancerClient loadBalancerClient) {
            this.discoveryClient = discoveryClient;
            this.loadBalancerClient = loadBalancerClient;
        }

        public ServiceInstance choose(String serviceId) {
            if (loadBalancerClient != null) {
                return loadBalancerClient.choose(serviceId);
            }
            // 降级：使用轮询
            return roundRobin(serviceId);
        }

        public ServiceInstance roundRobin(String serviceId) {
            var instances = discoveryClient.getInstances(serviceId);
            if (instances.isEmpty()) {
                return null;
            }
            // 简单轮询
            int index = (int) (System.currentTimeMillis() % instances.size());
            return instances.get(index);
        }
    }
}
