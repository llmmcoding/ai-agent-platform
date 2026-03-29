package com.aiagent.service.consistency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Saga 编排器
 * 管理跨服务的分布式事务
 * 支持正向执行和补偿回滚
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaOrchestrator {

    private final RedisTemplate<String, String> redisTemplate;
    private final EventSourcingService eventSourcingService;

    private static final String SAGA_PREFIX = "saga:";
    private static final String SAGA_STEP_PREFIX = "saga:step:";
    private static final Duration SAGA_TTL = Duration.ofHours(1);

    // 本地执行状态
    private final Map<String, SagaInstance> localInstances = new ConcurrentHashMap<>();

    /**
     * Saga 执行结果
     */
    public enum SagaResult {
        COMPLETED,
        COMPENSATED,
        FAILED
    }

    /**
     * Saga 步骤
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class SagaStep {
        private String stepId;
        private String serviceName;
        private String action;      // 执行的动作
        private String compensate;  // 补偿动作
        private Map<String, Object> params;
        private boolean completed;
        private boolean compensated;
    }

    /**
     * Saga 实例
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class SagaInstance {
        private String sagaId;
        private String agentId;
        private String sessionId;
        private List<SagaStep> steps;
        private int currentStep;
        private SagaResult result;
        private long startTime;
        private String errorMessage;
    }

    /**
     * 定义一个 Saga
     */
    public String defineSaga(String agentId, String sessionId, List<SagaStep> steps) {
        String sagaId = UUID.randomUUID().toString();
        SagaInstance instance = new SagaInstance();
        instance.setSagaId(sagaId);
        instance.setAgentId(agentId);
        instance.setSessionId(sessionId);
        instance.setSteps(steps);
        instance.setCurrentStep(0);
        instance.setResult(null);
        instance.setStartTime(System.currentTimeMillis());

        localInstances.put(sagaId, instance);

        // 记录 Saga 开始事件
        eventSourcingService.recordEvent(AgentStateEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .agentId(agentId)
                .sessionId(sessionId)
                .eventType("SAGA_STARTED")
                .payload(Map.of("sagaId", sagaId, "steps", steps.size()))
                .build());

        log.info("Defined saga: {}, steps: {}", sagaId, steps.size());
        return sagaId;
    }

    /**
     * 执行 Saga
     */
    public SagaResult executeSaga(String sagaId, java.util.function.Function<SagaStep, Boolean> stepExecutor) {
        SagaInstance instance = localInstances.get(sagaId);
        if (instance == null) {
            log.error("Saga not found: {}", sagaId);
            return SagaResult.FAILED;
        }

        try {
            // 正向执行每个步骤
            for (int i = instance.getCurrentStep(); i < instance.getSteps().size(); i++) {
                SagaStep step = instance.getSteps().get(i);
                instance.setCurrentStep(i);

                log.info("Executing saga step: {}, action: {}", step.getStepId(), step.getAction());

                // 记录步骤开始
                recordStepStart(sagaId, step);

                // 执行步骤
                boolean success = stepExecutor.apply(step);

                if (!success) {
                    instance.setErrorMessage("Step failed: " + step.getStepId());
                    instance.setResult(SagaResult.FAILED);
                    log.error("Saga step failed: {}, compensating...", step.getStepId());

                    // 执行补偿
                    compensate(sagaId, stepExecutor);
                    instance.setResult(SagaResult.COMPENSATED);
                    return instance.getResult();
                }

                recordStepComplete(sagaId, step);
            }

            instance.setResult(SagaResult.COMPLETED);
            log.info("Saga completed: {}", sagaId);
            return SagaResult.COMPLETED;

        } catch (Exception e) {
            log.error("Saga execution error: {}", sagaId, e);
            instance.setErrorMessage(e.getMessage());
            instance.setResult(SagaResult.FAILED);
            compensate(sagaId, stepExecutor);
            return SagaResult.COMPENSATED;
        }
    }

    /**
     * 补偿回滚
     */
    private void compensate(String sagaId, java.util.function.Function<SagaStep, Boolean> stepExecutor) {
        SagaInstance instance = localInstances.get(sagaId);
        if (instance == null) return;

        // 从当前步骤向前补偿
        for (int i = instance.getCurrentStep(); i >= 0; i--) {
            SagaStep step = instance.getSteps().get(i);
            if (step.isCompleted() && !step.isCompensated()) {
                log.info("Compensating step: {}", step.getStepId());

                try {
                    step.setCompensated(true);
                    recordStepCompensated(sagaId, step);

                    // 执行补偿逻辑
                    stepExecutor.apply(step);
                } catch (Exception e) {
                    log.error("Compensation failed for step: {}", step.getStepId(), e);
                    // 记录补偿失败，但继续补偿其他步骤
                    recordStepCompensationFailed(sagaId, step, e.getMessage());
                }
            }
        }
    }

    /**
     * 记录步骤开始
     */
    private void recordStepStart(String sagaId, SagaStep step) {
        String key = SAGA_STEP_PREFIX + sagaId + ":" + step.getStepId();
        redisTemplate.opsForHash().putAll(key, Map.of(
                "status", "STARTED",
                "startTime", String.valueOf(System.currentTimeMillis())
        ));
        redisTemplate.expire(key, SAGA_TTL);
    }

    /**
     * 记录步骤完成
     */
    private void recordStepComplete(String sagaId, SagaStep step) {
        String key = SAGA_STEP_PREFIX + sagaId + ":" + step.getStepId();
        redisTemplate.opsForHash().putAll(key, Map.of(
                "status", "COMPLETED",
                "endTime", String.valueOf(System.currentTimeMillis())
        ));
    }

    /**
     * 记录步骤已补偿
     */
    private void recordStepCompensated(String sagaId, SagaStep step) {
        String key = SAGA_STEP_PREFIX + sagaId + ":" + step.getStepId();
        redisTemplate.opsForHash().put(key, "status", "COMPENSATED");

        // 记录 Saga 事件
        eventSourcingService.recordEvent(AgentStateEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .agentId(localInstances.get(sagaId).getAgentId())
                .sessionId(localInstances.get(sagaId).getSessionId())
                .eventType("SAGA_STEP_COMPENSATED")
                .payload(Map.of("sagaId", sagaId, "stepId", step.getStepId()))
                .build());
    }

    /**
     * 记录补偿失败
     */
    private void recordStepCompensationFailed(String sagaId, SagaStep step, String error) {
        String key = SAGA_STEP_PREFIX + sagaId + ":" + step.getStepId();
        redisTemplate.opsForHash().putAll(key, Map.of(
                "status", "COMPENSATION_FAILED",
                "error", error
        ));
    }

    /**
     * 获取 Saga 状态
     */
    public Optional<SagaInstance> getSaga(String sagaId) {
        return Optional.ofNullable(localInstances.get(sagaId));
    }

    /**
     * 获取活跃的 Saga 数量
     */
    public long getActiveSagaCount() {
        return localInstances.values().stream()
                .filter(s -> s.getResult() == null)
                .count();
    }

    /**
     * 获取 Saga 统计
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalSagas", localInstances.size());
        stats.put("completed", localInstances.values().stream().filter(s -> s.getResult() == SagaResult.COMPLETED).count());
        stats.put("compensated", localInstances.values().stream().filter(s -> s.getResult() == SagaResult.COMPENSATED).count());
        stats.put("failed", localInstances.values().stream().filter(s -> s.getResult() == SagaResult.FAILED).count());
        stats.put("active", getActiveSagaCount());
        return stats;
    }
}
