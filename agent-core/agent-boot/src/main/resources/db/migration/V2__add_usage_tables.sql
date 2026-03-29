-- V2: 添加 API 使用量记录表和配额告警表
-- API 使用量记录表 (按小时聚合)
CREATE TABLE IF NOT EXISTS api_usage_record (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES ai_tenant(id),
    api_key_id BIGINT NOT NULL REFERENCES ai_tenant_api_key(id),
    record_hour TIMESTAMP NOT NULL,
    request_count BIGINT DEFAULT 0,
    input_tokens BIGINT DEFAULT 0,
    output_tokens BIGINT DEFAULT 0,
    total_tokens BIGINT DEFAULT 0,
    error_count BIGINT DEFAULT 0,
    total_latency_ms BIGINT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(tenant_id, api_key_id, record_hour)
);

CREATE INDEX IF NOT EXISTS idx_usage_tenant ON api_usage_record(tenant_id);
CREATE INDEX IF NOT EXISTS idx_usage_key ON api_usage_record(api_key_id);
CREATE INDEX IF NOT EXISTS idx_usage_hour ON api_usage_record(record_hour);

-- 租户配额告警表
CREATE TABLE IF NOT EXISTS tenant_quota_alert (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES ai_tenant(id),
    api_key_id BIGINT REFERENCES ai_tenant_api_key(id),
    alert_type VARCHAR(50) NOT NULL,
    threshold_value BIGINT NOT NULL,
    actual_value BIGINT NOT NULL,
    triggered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    acknowledged BOOLEAN DEFAULT FALSE,
    acknowledged_at TIMESTAMP,
    acknowledged_by VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_alert_tenant ON tenant_quota_alert(tenant_id);
CREATE INDEX IF NOT EXISTS idx_alert_triggered ON tenant_quota_alert(triggered_at);
CREATE INDEX IF NOT EXISTS idx_alert_ack ON tenant_quota_alert(acknowledged);
