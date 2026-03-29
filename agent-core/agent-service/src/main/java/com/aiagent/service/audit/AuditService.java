package com.aiagent.service.audit;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 审计日志服务
 */
public interface AuditService {

    /**
     * 记录审计事件
     */
    void logEvent(AuditEventType eventType, Long tenantId, Long apiKeyId,
                  String actor, String actorIp, Map<String, Object> details);

    /**
     * 简化版 - 仅需要事件类型和租户
     */
    void logEvent(AuditEventType eventType, Long tenantId, Long apiKeyId);

    /**
     * 查询审计日志
     */
    List<AuditLogEntry> queryLogs(Long tenantId, String eventType,
                                  LocalDateTime start, LocalDateTime end, int page, int size);

    /**
     * 获取审计日志条目
     */
    AuditLogEntry getLogById(Long id);

    /**
     * 审计日志条目
     */
    record AuditLogEntry(
            Long id,
            AuditEventType eventType,
            Long tenantId,
            Long apiKeyId,
            String actor,
            String actorIp,
            Map<String, Object> details,
            LocalDateTime createdAt
    ) {}
}
