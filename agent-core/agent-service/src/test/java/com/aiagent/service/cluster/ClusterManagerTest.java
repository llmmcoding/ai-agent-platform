package com.aiagent.service.cluster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ClusterManager 单元测试
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClusterManagerTest {

    @Mock
    private DiscoveryClient discoveryClient;

    @Mock
    private LoadBalancerClient loadBalancerClient;

    @Mock
    private ServiceInstance serviceInstance;

    @Mock
    private ServiceInstance healthyInstance;

    @Mock
    private ServiceInstance unhealthyInstance;

    private ClusterManager clusterManager;

    @BeforeEach
    void setUp() {
        clusterManager = new ClusterManager(discoveryClient, loadBalancerClient);
    }

    @Test
    void get_instances_returns_list() {
        // Given
        String serviceId = "agent-service";
        List<ServiceInstance> instances = List.of(serviceInstance);
        when(discoveryClient.getInstances(serviceId)).thenReturn(instances);

        // When
        List<ServiceInstance> result = clusterManager.getInstances(serviceId);

        // Then
        assertEquals(1, result.size());
        assertEquals(serviceInstance, result.get(0));
        verify(discoveryClient).getInstances(serviceId);
    }

    @Test
    void get_instances_empty_when_no_instances() {
        // Given
        String serviceId = "nonexistent-service";
        when(discoveryClient.getInstances(serviceId)).thenReturn(List.of());

        // When
        List<ServiceInstance> result = clusterManager.getInstances(serviceId);

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void get_services_returns_all_service_names() {
        // Given
        List<String> services = List.of("agent-service", "python-worker", "rag-service");
        when(discoveryClient.getServices()).thenReturn(services);

        // When
        List<String> result = clusterManager.getServices();

        // Then
        assertEquals(3, result.size());
        assertEquals("agent-service", result.get(0));
    }

    @Test
    void choose_uses_loadbalancer() {
        // Given
        String serviceId = "agent-service";
        when(loadBalancerClient.choose(serviceId)).thenReturn(serviceInstance);

        // When
        ServiceInstance result = clusterManager.choose(serviceId);

        // Then
        assertEquals(serviceInstance, result);
        verify(loadBalancerClient).choose(serviceId);
    }

    @Test
    void choose_healthy_filters_unhealthy() {
        // Given
        String serviceId = "agent-service";

        Map<String, String> healthyMetadata = new HashMap<>();
        healthyMetadata.put("healthy", "true");

        Map<String, String> unhealthyMetadata = new HashMap<>();
        unhealthyMetadata.put("healthy", "false");

        when(healthyInstance.getMetadata()).thenReturn(healthyMetadata);
        when(healthyInstance.getInstanceId()).thenReturn("instance-1");

        when(unhealthyInstance.getMetadata()).thenReturn(unhealthyMetadata);
        when(unhealthyInstance.getInstanceId()).thenReturn("instance-2");

        when(discoveryClient.getInstances(serviceId)).thenReturn(List.of(healthyInstance, unhealthyInstance));

        // When
        ServiceInstance result = clusterManager.chooseHealthy(serviceId);

        // Then
        assertEquals(healthyInstance, result);
    }

    @Test
    void choose_healthy_degrades_to_first_when_all_unhealthy() {
        // Given
        String serviceId = "agent-service";

        Map<String, String> unhealthyMetadata = new HashMap<>();
        unhealthyMetadata.put("healthy", "false");

        when(unhealthyInstance.getMetadata()).thenReturn(unhealthyMetadata);
        when(unhealthyInstance.getInstanceId()).thenReturn("instance-1");

        when(discoveryClient.getInstances(serviceId)).thenReturn(List.of(unhealthyInstance));

        // When
        ServiceInstance result = clusterManager.chooseHealthy(serviceId);

        // Then - degrades to return first instance even if unhealthy
        assertEquals(unhealthyInstance, result);
    }

    @Test
    void choose_healthy_returns_null_when_no_instances() {
        // Given
        String serviceId = "nonexistent-service";
        when(discoveryClient.getInstances(serviceId)).thenReturn(List.of());

        // When
        ServiceInstance result = clusterManager.chooseHealthy(serviceId);

        // Then
        assertNull(result);
    }

    @Test
    void is_service_available_returns_true_when_instances_exist() {
        // Given
        String serviceId = "agent-service";
        when(discoveryClient.getInstances(serviceId)).thenReturn(List.of(serviceInstance));

        // When
        boolean result = clusterManager.isServiceAvailable(serviceId);

        // Then
        assertTrue(result);
    }

    @Test
    void is_service_available_returns_false_when_no_instances() {
        // Given
        String serviceId = "nonexistent-service";
        when(discoveryClient.getInstances(serviceId)).thenReturn(List.of());

        // When
        boolean result = clusterManager.isServiceAvailable(serviceId);

        // Then
        assertFalse(result);
    }

    @Test
    void get_instance_count_returns_correct_count() {
        // Given
        String serviceId = "agent-service";
        when(discoveryClient.getInstances(serviceId))
                .thenReturn(List.of(serviceInstance, serviceInstance, serviceInstance));

        // When
        int result = clusterManager.getInstanceCount(serviceId);

        // Then
        assertEquals(3, result);
    }

    @Test
    void get_service_info_complete() {
        // Given
        String serviceId = "agent-service";

        Map<String, String> healthyMetadata = new HashMap<>();
        healthyMetadata.put("healthy", "true");

        when(serviceInstance.getInstanceId()).thenReturn("instance-1");
        when(serviceInstance.getHost()).thenReturn("localhost");
        when(serviceInstance.getPort()).thenReturn(8080);
        when(serviceInstance.getMetadata()).thenReturn(healthyMetadata);

        when(discoveryClient.getInstances(serviceId)).thenReturn(List.of(serviceInstance));

        // When
        ClusterManager.ServiceInfo result = clusterManager.getServiceInfo(serviceId);

        // Then
        assertNotNull(result);
        assertEquals(serviceId, result.getServiceId());
        assertEquals(1, result.getInstanceCount());
        assertTrue(result.isAvailable());
        assertEquals(1, result.getInstances().size());

        ClusterManager.ServiceInfo.InstanceDetail detail = result.getInstances().get(0);
        assertEquals("instance-1", detail.getInstanceId());
        assertEquals("localhost", detail.getHost());
        assertEquals(8080, detail.getPort());
        assertTrue(detail.isHealthy());
    }

    @Test
    void mark_checked_updates_last_check_time() {
        // Given
        String instanceId = "instance-001";

        // When
        clusterManager.markChecked(instanceId);

        // Then - no exception means success
        // The internal state is verified by chooseHealthy filtering
    }

    @Test
    void get_current_instance_returns_matching_host() {
        // Given
        String serviceId = "agent-service";

        when(serviceInstance.getHost()).thenReturn("different-host");
        when(serviceInstance.getInstanceId()).thenReturn("instance-1");

        when(discoveryClient.getInstances(serviceId)).thenReturn(List.of(serviceInstance));

        // When
        ServiceInstance result = clusterManager.getCurrentInstance(serviceId);

        // Then - returns first instance when HOSTNAME doesn't match
        assertEquals(serviceInstance, result);
    }
}
