package com.aiagent.service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Gauge;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 监控指标 - 参考 OpenClaw infra 监控 + llmgateway 指标体系
 * 提供 QPS、延迟、错误率、Token 使用等完整指标
 */
@Slf4j
@Component
public class AgentMetrics {

    private final MeterRegistry meterRegistry;

    // 计数器
    private final Counter requestCounter;
    private final Counter successCounter;
    private final Counter errorCounter;
    private final Counter toolCallCounter;
    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;
    private final Counter llmCallCounter;

    // Token 计数器 (借鉴 llmgateway)
    private final Counter totalPromptTokens;
    private final Counter totalCompletionTokens;
    private final Counter totalTokens;

    // 定时器
    private final Timer requestLatencyTimer;
    private final Timer toolExecutionTimer;
    private final Timer llmCallTimer;
    private final Timer memoryOperationTimer;
    private final Timer ragRetrievalTimer;

    // 原子变量
    private final AtomicLong activeRequests = new AtomicLong(0);
    private final AtomicLong totalTokenUsage = new AtomicLong(0);

    public AgentMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        // 初始化计数器
        this.requestCounter = Counter.builder("agent_requests_total")
                .description("Total number of agent requests")
                .tag("type", "all")
                .register(meterRegistry);

        this.successCounter = Counter.builder("agent_requests_success")
                .description("Total number of successful agent requests")
                .register(meterRegistry);

        this.errorCounter = Counter.builder("agent_requests_error")
                .description("Total number of failed agent requests")
                .register(meterRegistry);

        this.toolCallCounter = Counter.builder("agent_tool_calls_total")
                .description("Total number of tool calls")
                .register(meterRegistry);

        this.cacheHitCounter = Counter.builder("agent_cache_hits_total")
                .description("Total number of cache hits")
                .register(meterRegistry);

        this.cacheMissCounter = Counter.builder("agent_cache_misses_total")
                .description("Total number of cache misses")
                .register(meterRegistry);

        this.llmCallCounter = Counter.builder("agent_llm_calls_total")
                .description("Total number of LLM API calls")
                .register(meterRegistry);

        // Token 计数器 (借鉴 llmgateway)
        this.totalPromptTokens = Counter.builder("llm_input_tokens")
                .description("Total prompt tokens")
                .register(meterRegistry);

        this.totalCompletionTokens = Counter.builder("llm_output_tokens")
                .description("Total completion tokens")
                .register(meterRegistry);

        this.totalTokens = Counter.builder("llm_total_tokens")
                .description("Total tokens (prompt + completion)")
                .register(meterRegistry);

        // 初始化定时器
        this.requestLatencyTimer = Timer.builder("agent_request_latency")
                .description("Agent request latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry);

        this.toolExecutionTimer = Timer.builder("agent_tool_execution")
                .description("Tool execution time")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        this.llmCallTimer = Timer.builder("agent_llm_call_latency")
                .description("LLM API call latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry);

        this.memoryOperationTimer = Timer.builder("agent_memory_operation")
                .description("Memory operation time")
                .register(meterRegistry);

        this.ragRetrievalTimer = Timer.builder("agent_rag_retrieval")
                .description("RAG retrieval time")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        // 初始化 Gauge
        Gauge.builder("agent_active_requests", activeRequests, AtomicLong::get)
                .description("Number of active requests")
                .register(meterRegistry);

        Gauge.builder("agent_total_token_usage", totalTokenUsage, AtomicLong::get)
                .description("Total token usage")
                .register(meterRegistry);
    }

    /**
     * 记录请求
     */
    public void recordRequest() {
        requestCounter.increment();
        activeRequests.incrementAndGet();
    }

    /**
     * 记录请求完成
     */
    public void recordRequestComplete(long latencyMs, boolean success) {
        activeRequests.decrementAndGet();
        requestLatencyTimer.record(latencyMs, TimeUnit.MILLISECONDS);

        if (success) {
            successCounter.increment();
        } else {
            errorCounter.increment();
        }
    }

    /**
     * 记录工具调用
     */
    public void recordToolCall(long latencyMs) {
        toolCallCounter.increment();
        toolExecutionTimer.record(latencyMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录 LLM 调用
     */
    public void recordLLMCall(long latencyMs) {
        llmCallCounter.increment();
        llmCallTimer.record(latencyMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录缓存命中
     */
    public void recordCacheHit() {
        cacheHitCounter.increment();
    }

    /**
     * 记录缓存未命中
     */
    public void recordCacheMiss() {
        cacheMissCounter.increment();
    }

    /**
     * 记录 Token 使用 (借鉴 llmgateway)
     */
    public void recordTokenUsage(int promptTokens, int completionTokens) {
        totalPromptTokens.increment(promptTokens);
        totalCompletionTokens.increment(completionTokens);
        totalTokens.increment(promptTokens + completionTokens);
        totalTokenUsage.addAndGet(promptTokens + completionTokens);
    }

    /**
     * 记录 RAG 检索时间
     */
    public void recordRagRetrieval(long latencyMs) {
        ragRetrievalTimer.record(latencyMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录记忆操作时间
     */
    public void recordMemoryOperation(long latencyMs) {
        memoryOperationTimer.record(latencyMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 获取当前活跃请求数
     */
    public long getActiveRequests() {
        return activeRequests.get();
    }

    /**
     * 获取缓存命中率
     */
    public double getCacheHitRate() {
        double hits = cacheHitCounter.count();
        double misses = cacheMissCounter.count();
        double total = hits + misses;
        return total > 0 ? hits / total : 0.0;
    }

    /**
     * 记录向量搜索延迟
     */
    public void recordVectorSearchLatency(String provider, double latencyMs) {
        Timer.builder("agent_vector_search_latency")
                .tag("provider", provider)
                .register(meterRegistry)
                .record((long) latencyMs, TimeUnit.MILLISECONDS);
    }

    /**
     * RAG 降级触发次数
     */
    public void incrementRAGDegradeTriggered() {
        Counter.builder("agent_rag_degrade_triggered_total")
                .description("Total number of RAG degradation triggers")
                .register(meterRegistry)
                .increment();
    }

    /**
     * RAG 召回失败次数
     */
    public void incrementRAGRecallFailures() {
        Counter.builder("agent_rag_recall_failures_total")
                .description("Total number of RAG recall failures")
                .register(meterRegistry)
                .increment();
    }

    /**
     * RAG 缓存命中
     */
    public void incrementRAGCacheHit() {
        cacheHitCounter.increment();
    }

    /**
     * RAG 缓存未命中
     */
    public void incrementRAGCacheMiss() {
        cacheMissCounter.increment();
    }

    /**
     * 记录实验结果
     */
    public void recordExperimentResult(String experimentId, String variantId, boolean success, long latencyMs) {
        Counter.builder("agent_experiment_results_total")
                .tag("experiment_id", experimentId)
                .tag("variant_id", variantId)
                .tag("success", String.valueOf(success))
                .description("Total number of experiment results")
                .register(meterRegistry)
                .increment();

        Timer.builder("agent_experiment_latency")
                .tag("experiment_id", experimentId)
                .tag("variant_id", variantId)
                .tag("success", String.valueOf(success))
                .description("Experiment latency")
                .register(meterRegistry)
                .record(latencyMs, TimeUnit.MILLISECONDS);
    }
}
