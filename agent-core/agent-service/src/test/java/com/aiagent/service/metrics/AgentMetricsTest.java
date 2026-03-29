package com.aiagent.service.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentMetrics 单元测试
 */
class AgentMetricsTest {

    private MeterRegistry meterRegistry;
    private AgentMetrics agentMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        agentMetrics = new AgentMetrics(meterRegistry);
    }

    @Test
    void record_request_increments_counter() {
        // When
        agentMetrics.recordRequest();
        agentMetrics.recordRequest();
        agentMetrics.recordRequest();

        // Then
        double count = meterRegistry.find("agent_requests_total").counter().count();
        assertEquals(3.0, count);
    }

    @Test
    void record_success_increments_success_counter() {
        // When
        agentMetrics.recordRequestComplete(1000, true);

        // Then
        double successCount = meterRegistry.find("agent_requests_success").counter().count();
        double errorCount = meterRegistry.find("agent_requests_error").counter().count();
        assertEquals(1.0, successCount);
        assertEquals(0.0, errorCount);
    }

    @Test
    void record_error_increments_error_counter() {
        // When
        agentMetrics.recordRequestComplete(1000, false);

        // Then
        double successCount = meterRegistry.find("agent_requests_success").counter().count();
        double errorCount = meterRegistry.find("agent_requests_error").counter().count();
        assertEquals(0.0, successCount);
        assertEquals(1.0, errorCount);
    }

    @Test
    void active_requests_gauge_tracks_concurrent() {
        // When - simulate concurrent requests
        agentMetrics.recordRequest(); // active = 1
        agentMetrics.recordRequest(); // active = 2
        agentMetrics.recordRequestComplete(100, true); // active = 1
        agentMetrics.recordRequestComplete(100, true); // active = 0

        // Then
        long activeRequests = agentMetrics.getActiveRequests();
        assertEquals(0, activeRequests);
    }

    @Test
    void cache_hit_rate_calculation() {
        // Given - record some cache hits and misses
        agentMetrics.recordCacheHit();
        agentMetrics.recordCacheHit();
        agentMetrics.recordCacheHit();
        agentMetrics.recordCacheMiss();
        agentMetrics.recordCacheMiss();

        // When
        double hitRate = agentMetrics.getCacheHitRate();

        // Then - 3 hits out of 5 total = 0.6
        assertEquals(0.6, hitRate, 0.0001);
    }

    @Test
    void cache_hit_rate_returns_zero_when_no_data() {
        // When
        double hitRate = agentMetrics.getCacheHitRate();

        // Then
        assertEquals(0.0, hitRate);
    }

    @Test
    void token_usage_recorded() {
        // When
        agentMetrics.recordTokenUsage(100, 50);

        // Then
        double promptTokens = meterRegistry.find("llm_input_tokens").counter().count();
        double completionTokens = meterRegistry.find("llm_output_tokens").counter().count();
        double totalTokens = meterRegistry.find("llm_total_tokens").counter().count();

        assertEquals(100.0, promptTokens);
        assertEquals(50.0, completionTokens);
        assertEquals(150.0, totalTokens); // prompt + completion
    }

    @Test
    void tool_call_records_counter_and_timer() {
        // When
        agentMetrics.recordToolCall(500);

        // Then
        double toolCallCount = meterRegistry.find("agent_tool_calls_total").counter().count();
        assertEquals(1.0, toolCallCount);
    }

    @Test
    void llm_call_records_counter_and_timer() {
        // When
        agentMetrics.recordLLMCall(1000);

        // Then
        double llmCallCount = meterRegistry.find("agent_llm_calls_total").counter().count();
        assertEquals(1.0, llmCallCount);
    }

    @Test
    void rag_retrieval_records_timer() {
        // When
        agentMetrics.recordRagRetrieval(200);

        // Then - no exception means success
        double ragTimer = meterRegistry.find("agent_rag_retrieval").timer().count();
        assertEquals(1, ragTimer);
    }

    @Test
    void memory_operation_records_timer() {
        // When
        agentMetrics.recordMemoryOperation(50);

        // Then
        double memoryTimer = meterRegistry.find("agent_memory_operation").timer().count();
        assertEquals(1, memoryTimer);
    }

    @Test
    void get_active_requests_returns_current_value() {
        // Given
        agentMetrics.recordRequest();
        agentMetrics.recordRequest();

        // When
        long active = agentMetrics.getActiveRequests();

        // Then
        assertEquals(2, active);
    }

    @Test
    void multiple_requests_complete_decrement_correctly() {
        // Given - simulate 5 requests
        for (int i = 0; i < 5; i++) {
            agentMetrics.recordRequest();
        }

        // When - 3 complete successfully, 1 fails
        agentMetrics.recordRequestComplete(100, true);
        agentMetrics.recordRequestComplete(100, true);
        agentMetrics.recordRequestComplete(100, true);
        agentMetrics.recordRequestComplete(100, false);

        // Then
        assertEquals(1, agentMetrics.getActiveRequests());
        assertEquals(3.0, meterRegistry.find("agent_requests_success").counter().count());
        assertEquals(1.0, meterRegistry.find("agent_requests_error").counter().count());
    }
}
