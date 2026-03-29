package com.aiagent.service.experiment;

import com.aiagent.service.metrics.AgentMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prompt A/B 测试服务
 * 支持多实验并行、流量分配、效果追踪
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptExperimentService {

    private final RedisTemplate<String, String> redisTemplate;
    private final AgentMetrics agentMetrics;

    @Value("${aiagent.prompt-experiment.enabled:true}")
    private boolean experimentEnabled;

    @Value("${aiagent.prompt-experiment.default-variant:control}")
    private String defaultVariant;

    // 实验配置: experimentId -> List<PromptVariant>
    private final Map<String, List<PromptVariant>> experiments = new ConcurrentHashMap<>();

    // 实验统计: experimentId:variantId -> ExperimentStats
    private final Map<String, ExperimentStats> experimentStats = new ConcurrentHashMap<>();

    // 用户分配缓存: experimentId:userId -> variantId
    private final Map<String, String> userVariantCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 默认注册几个常用实验
        registerDefaultExperiments();
        log.info("PromptExperimentService initialized, enabled={}", experimentEnabled);
    }

    /**
     * 注册默认实验
     */
    private void registerDefaultExperiments() {
        // RAG System Prompt 实验
        registerExperiment("rag_system_prompt", List.of(
                PromptVariant.builder()
                        .id("control")
                        .promptTemplate("你是一个有用的AI助手。请根据提供的上下文信息回答用户问题。")
                        .trafficWeight(0.5)
                        .enabled(true)
                        .build(),
                PromptVariant.builder()
                        .id("enhanced")
                        .promptTemplate("你是一个专业的技术顾问。请仔细分析上下文信息，提供准确、详细的回答。如果信息不足，请明确指出。")
                        .trafficWeight(0.5)
                        .enabled(true)
                        .build()
        ));

        // Agent Behavior 实验
        registerExperiment("agent_behavior", List.of(
                PromptVariant.builder()
                        .id("concise")
                        .promptTemplate("简洁明了地回答问题。")
                        .trafficWeight(0.33)
                        .enabled(true)
                        .build(),
                PromptVariant.builder()
                        .id("detailed")
                        .promptTemplate("详细全面地回答问题，包含背景知识和最佳实践。")
                        .trafficWeight(0.33)
                        .enabled(true)
                        .build(),
                PromptVariant.builder()
                        .id("balanced")
                        .promptTemplate("在简洁和详细之间保持平衡，适当解释关键点。")
                        .trafficWeight(0.34)
                        .enabled(true)
                        .build()
        ));
    }

    /**
     * 注册实验
     */
    public void registerExperiment(String experimentId, List<PromptVariant> variants) {
        // 验证权重总和
        double totalWeight = variants.stream()
                .filter(PromptVariant::isEnabled)
                .mapToDouble(PromptVariant::getTrafficWeight)
                .sum();

        if (Math.abs(totalWeight - 1.0) > 0.01) {
            log.warn("Experiment {} has weights {} not summing to 1.0, normalizing", experimentId, totalWeight);
            // 归一化权重
            double finalTotal = totalWeight;
            variants.forEach(v -> {
                if (v.isEnabled()) {
                    v.setTrafficWeight(v.getTrafficWeight() / finalTotal);
                }
            });
        }

        experiments.put(experimentId, variants);
        log.info("Registered experiment {} with {} variants", experimentId, variants.size());
    }

    /**
     * 选择实验变体
     */
    public String selectVariant(String experimentId, String userId) {
        if (!experimentEnabled) {
            return defaultVariant;
        }

        List<PromptVariant> variants = experiments.get(experimentId);
        if (variants == null || variants.isEmpty()) {
            log.warn("Experiment {} not found, using default variant", experimentId);
            return defaultVariant;
        }

        // 检查用户缓存
        String cacheKey = experimentId + ":" + userId;
        if (userId != null && userVariantCache.containsKey(cacheKey)) {
            return userVariantCache.get(cacheKey);
        }

        // 流量分配 (基于随机选择)
        double rand = Math.random();
        double cumulative = 0;
        String selectedVariant = defaultVariant;

        for (PromptVariant variant : variants) {
            if (!variant.isEnabled()) {
                continue;
            }
            cumulative += variant.getTrafficWeight();
            if (rand <= cumulative) {
                selectedVariant = variant.getId();
                break;
            }
        }

        // 缓存用户分配
        if (userId != null) {
            userVariantCache.put(cacheKey, selectedVariant);
        }

        log.debug("Selected variant {} for experiment {} user {}", selectedVariant, experimentId, userId);
        return selectedVariant;
    }

    /**
     * 获取实验变体的 Prompt
     */
    public String getPrompt(String experimentId, String userId) {
        String variantId = selectVariant(experimentId, userId);
        return getVariantPrompt(experimentId, variantId);
    }

    /**
     * 获取指定变体的 Prompt
     */
    public String getVariantPrompt(String experimentId, String variantId) {
        List<PromptVariant> variants = experiments.get(experimentId);
        if (variants == null) {
            return null;
        }

        return variants.stream()
                .filter(v -> v.getId().equals(variantId))
                .map(PromptVariant::getPromptTemplate)
                .findFirst()
                .orElse(null);
    }

    /**
     * 记录实验结果
     */
    public void recordResult(String experimentId, String variantId,
                             boolean success, long latencyMs, double score) {
        String statsKey = experimentId + ":" + variantId;
        ExperimentStats stats = experimentStats.computeIfAbsent(statsKey, k -> new ExperimentStats());

        stats.totalRequests.incrementAndGet();
        if (success) {
            stats.successfulRequests.incrementAndGet();
        }
        stats.totalLatencyMs.addAndGet(latencyMs);
        stats.totalScore.addAndGet((long) (score * 1000));

        // 记录到 Redis
        try {
            String redisKey = "experiment:" + experimentId + ":" + variantId;
            redisTemplate.opsForHash().increment(redisKey, "total", 1);
            if (success) {
                redisTemplate.opsForHash().increment(redisKey, "success", 1);
            }
            redisTemplate.opsForHash().increment(redisKey, "latency", latencyMs);
            redisTemplate.expire(redisKey, 7, TimeUnit.DAYS);
        } catch (Exception e) {
            log.warn("Failed to record experiment result to Redis: {}", e.getMessage());
        }

        // 更新 Metrics
        if (agentMetrics != null) {
            agentMetrics.recordExperimentResult(experimentId, variantId, success, latencyMs);
        }

        log.debug("Recorded experiment result: experiment={}, variant={}, success={}, latency={}ms",
                experimentId, variantId, success, latencyMs);
    }

    /**
     * 获取实验统计
     */
    public Map<String, Object> getExperimentStats(String experimentId) {
        List<PromptVariant> variants = experiments.get(experimentId);
        if (variants == null) {
            return Collections.emptyMap();
        }

        Map<String, Object> stats = new HashMap<>();
        List<Map<String, Object>> variantStatsList = new ArrayList<>();

        for (PromptVariant variant : variants) {
            String statsKey = experimentId + ":" + variant.getId();
            ExperimentStats statsData = experimentStats.get(statsKey);

            Map<String, Object> vStats = new HashMap<>();
            vStats.put("variantId", variant.getId());
            vStats.put("weight", variant.getTrafficWeight());
            vStats.put("enabled", variant.isEnabled());

            if (statsData != null) {
                vStats.put("totalRequests", statsData.totalRequests.get());
                vStats.put("successfulRequests", statsData.successfulRequests.get());
                vStats.put("successRate", statsData.totalRequests.get() > 0
                        ? (double) statsData.successfulRequests.get() / statsData.totalRequests.get()
                        : 0.0);
                vStats.put("avgLatencyMs", statsData.totalRequests.get() > 0
                        ? statsData.totalLatencyMs.get() / statsData.totalRequests.get()
                        : 0.0);
                vStats.put("avgScore", statsData.totalRequests.get() > 0
                        ? statsData.totalScore.get() / (double) statsData.totalRequests.get() / 1000.0
                        : 0.0);
            } else {
                vStats.put("totalRequests", 0);
                vStats.put("successfulRequests", 0);
                vStats.put("successRate", 0.0);
                vStats.put("avgLatencyMs", 0.0);
                vStats.put("avgScore", 0.0);
            }

            variantStatsList.add(vStats);
        }

        stats.put("experimentId", experimentId);
        stats.put("enabled", experimentEnabled);
        stats.put("variants", variantStatsList);

        return stats;
    }

    /**
     * 获取所有实验
     */
    public Set<String> getAllExperiments() {
        return experiments.keySet();
    }

    /**
     * 启用/禁用实验
     */
    public void setExperimentEnabled(String experimentId, boolean enabled) {
        List<PromptVariant> variants = experiments.get(experimentId);
        if (variants != null) {
            variants.forEach(v -> v.setEnabled(enabled));
            log.info("Experiment {} enabled={}", experimentId, enabled);
        }
    }

    /**
     * 实验统计
     */
    private static class ExperimentStats {
        AtomicLong totalRequests = new AtomicLong(0);
        AtomicLong successfulRequests = new AtomicLong(0);
        AtomicLong totalLatencyMs = new AtomicLong(0);
        AtomicLong totalScore = new AtomicLong(0);
    }
}
