package com.aiagent.service.audit;

/**
 * 审计事件类型
 */
public enum AuditEventType {
    // 租户事件
    TENANT_CREATED,
    TENANT_UPDATED,
    TENANT_DEACTIVATED,

    // API Key 事件
    API_KEY_CREATED,
    API_KEY_ROTATED,
    API_KEY_DEACTIVATED,
    API_KEY_UPDATED,

    // 配额告警事件
    QUOTA_ALERT_TRIGGERED,
    QUOTA_ALERT_ACKNOWLEDGED,

    // API 请求事件
    API_REQUEST_COMPLETED
}
