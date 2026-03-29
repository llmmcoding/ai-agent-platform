package com.aiagent.service.consistency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 事件溯源服务
 * 记录和重放 Agent 状态变更事件
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventSourcingService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String EVENT_PREFIX = "es:event:";
    private static final String AGENT_EVENTS_PREFIX = "es:agent:";
    private static final String SNAPSHOT_PREFIX = "es:snapshot:";
    private static final int MAX_EVENTS_PER_AGGREGATE = 1000;
    private static final Duration EVENT_TTL = Duration.ofDays(7);

    // 本地事件缓冲区 (减少 Redis 访问)
    private final Map<String, List<AgentStateEvent>> localBuffer = new ConcurrentHashMap<>();
    private static final int BUFFER_SIZE = 100;

    /**
     * 记录事件
     */
    public void recordEvent(AgentStateEvent event) {
        if (event.getEventId() == null) {
            event.setEventId(UUID.randomUUID().toString());
        }
        if (event.getTimestamp() == null) {
            event.setTimestamp(Instant.now());
        }

        String key = EVENT_PREFIX + event.getEventId();

        try {
            String eventJson = objectMapper.writeValueAsString(event);
            redisTemplate.opsForValue().set(key, eventJson, EVENT_TTL);

            // 添加到 aggregate 索引
            String aggregateKey = AGENT_EVENTS_PREFIX + event.getAgentId();
            redisTemplate.opsForZSet().add(aggregateKey, event.getEventId(),
                    event.getTimestamp().toEpochMilli());

            // 限制 aggregate 中的事件数量
            Long size = redisTemplate.opsForZSet().size(aggregateKey);
            if (size != null && size > MAX_EVENTS_PER_AGGREGATE) {
                // 删除最旧的事件
                redisTemplate.opsForZSet().removeRange(aggregateKey, 0, size - MAX_EVENTS_PER_AGGREGATE - 1);
            }

            // 本地缓冲
            addToLocalBuffer(event);

            log.debug("Recorded event: type={}, agentId={}, eventId={}",
                    event.getEventType(), event.getAgentId(), event.getEventId());

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event: {}", event, e);
        }
    }

    /**
     * 获取聚合的所有事件
     */
    public List<AgentStateEvent> getEvents(String agentId) {
        return getEvents(agentId, 0, -1);
    }

    /**
     * 获取聚合的事件 (分页)
     */
    public List<AgentStateEvent> getEvents(String agentId, long offset, long limit) {
        String aggregateKey = AGENT_EVENTS_PREFIX + agentId;

        Set<String> eventIds;
        if (offset == 0 && limit < 0) {
            // 获取所有
            eventIds = redisTemplate.opsForZSet().range(aggregateKey, 0, -1);
        } else {
            long start = offset;
            long end = limit < 0 ? -1 : offset + limit - 1;
            eventIds = redisTemplate.opsForZSet().range(aggregateKey, start, end);
        }

        if (eventIds == null || eventIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<AgentStateEvent> events = new ArrayList<>();
        for (String eventId : eventIds) {
            String key = EVENT_PREFIX + eventId;
            String eventJson = redisTemplate.opsForValue().get(key);
            if (eventJson != null) {
                try {
                    AgentStateEvent event = objectMapper.readValue(eventJson, AgentStateEvent.class);
                    events.add(event);
                } catch (JsonProcessingException e) {
                    log.warn("Failed to deserialize event: {}", eventId, e);
                }
            }
        }

        // 按时间戳排序
        events.sort(Comparator.comparing(AgentStateEvent::getTimestamp));
        return events;
    }

    /**
     * 获取会话的所有事件
     */
    public List<AgentStateEvent> getSessionEvents(String sessionId) {
        // 扫描所有 agent 事件，筛选 sessionId 匹配的
        // 实际生产中应该有 session->events 的索引
        List<AgentStateEvent> sessionEvents = new ArrayList<>();

        // 这里简化处理，实际应该维护 session->eventIds 的索引
        log.warn("getSessionEvents is not optimized - scanning all events for session: {}", sessionId);

        return sessionEvents;
    }

    /**
     * 获取最后 N 个事件
     */
    public List<AgentStateEvent> getLastEvents(String agentId, int count) {
        String aggregateKey = AGENT_EVENTS_PREFIX + agentId;
        Set<String> eventIds = redisTemplate.opsForZSet().reverseRange(aggregateKey, 0, count - 1);

        if (eventIds == null || eventIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<AgentStateEvent> events = new ArrayList<>();
        for (String eventId : eventIds) {
            String key = EVENT_PREFIX + eventId;
            String eventJson = redisTemplate.opsForValue().get(key);
            if (eventJson != null) {
                try {
                    AgentStateEvent event = objectMapper.readValue(eventJson, AgentStateEvent.class);
                    events.add(event);
                } catch (JsonProcessingException e) {
                    log.warn("Failed to deserialize event: {}", eventId, e);
                }
            }
        }

        // 逆转排序 (从旧到新)
        Collections.reverse(events);
        return events;
    }

    /**
     * 创建快照
     */
    public void createSnapshot(String agentId, Object state) {
        String snapshotKey = SNAPSHOT_PREFIX + agentId;

        Map<String, Object> snapshotData = new HashMap<>();
        snapshotData.put("agentId", agentId);
        snapshotData.put("state", state);
        snapshotData.put("timestamp", Instant.now().toString());
        snapshotData.put("version", getEvents(agentId).size());

        try {
            String snapshotJson = objectMapper.writeValueAsString(snapshotData);
            // 快照保留 30 天
            redisTemplate.opsForValue().set(snapshotKey, snapshotJson, 30, TimeUnit.DAYS);
            log.info("Created snapshot for agent: {}, version: {}", agentId, snapshotData.get("version"));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize snapshot for agent: {}", agentId, e);
        }
    }

    /**
     * 获取最新快照
     */
    public Optional<Object> getLatestSnapshot(String agentId) {
        String snapshotKey = SNAPSHOT_PREFIX + agentId;
        String snapshotJson = redisTemplate.opsForValue().get(snapshotKey);

        if (snapshotJson == null) {
            return Optional.empty();
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> snapshotData = objectMapper.readValue(snapshotJson, Map.class);
            return Optional.ofNullable(snapshotData.get("state"));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize snapshot for agent: {}", agentId, e);
            return Optional.empty();
        }
    }

    /**
     * 重放事件 (从快照或从头)
     */
    public List<AgentStateEvent> replay(String agentId, Integer fromVersion) {
        List<AgentStateEvent> events;

        if (fromVersion != null && fromVersion > 0) {
            // 从指定版本重放
            events = getEvents(agentId, fromVersion, -1);
        } else {
            // 尝试从快照恢复
            Optional<Object> snapshot = getLatestSnapshot(agentId);
            if (snapshot.isPresent()) {
                log.info("Found snapshot for agent: {}, replaying from snapshot", agentId);
                // 从快照版本 + 1 开始重放
                events = getEvents(agentId);
            } else {
                // 从头重放
                events = getEvents(agentId);
            }
        }

        log.info("Replaying {} events for agent: {}", events.size(), agentId);
        return events;
    }

    /**
     * 添加到本地缓冲
     */
    private void addToLocalBuffer(AgentStateEvent event) {
        String key = event.getAgentId();
        List<AgentStateEvent> buffer = localBuffer.computeIfAbsent(key, k -> new ArrayList<>());
        synchronized (buffer) {
            buffer.add(event);
            if (buffer.size() > BUFFER_SIZE) {
                buffer.remove(0);
            }
        }
    }

    /**
     * 获取事件统计
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();

        Set<String> eventKeys = redisTemplate.keys(EVENT_PREFIX + "*");
        Set<String> agentKeys = redisTemplate.keys(AGENT_EVENTS_PREFIX + "*");
        Set<String> snapshotKeys = redisTemplate.keys(SNAPSHOT_PREFIX + "*");

        stats.put("totalEvents", eventKeys != null ? eventKeys.size() : 0);
        stats.put("totalAgents", agentKeys != null ? agentKeys.size() : 0);
        stats.put("totalSnapshots", snapshotKeys != null ? snapshotKeys.size() : 0);

        return stats;
    }
}
