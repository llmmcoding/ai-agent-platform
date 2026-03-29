package com.aiagent.service.alert;

import com.aiagent.common.dto.QuotaAlert;

import java.util.List;

/**
 * 配额告警服务
 */
public interface QuotaAlertService {

    /**
     * 检查并触发告警
     */
    void checkAndAlert(Long tenantId, Long apiKeyId, String limitType,
                       long limit, long current);

    /**
     * 获取租户未确认的告警
     */
    List<QuotaAlert> getUnacknowledgedAlerts(Long tenantId);

    /**
     * 获取租户所有告警
     */
    List<QuotaAlert> getAlerts(Long tenantId, boolean acknowledgedOnly);

    /**
     * 确认告警
     */
    void acknowledgeAlert(Long alertId, String acknowledgedBy);

    /**
     * 清理过期告警 (超过 7 天的)
     */
    void cleanupOldAlerts();
}
