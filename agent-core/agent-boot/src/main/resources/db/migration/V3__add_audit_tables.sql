-- V3: 添加审计日志表
CREATE TABLE IF NOT EXISTS api_audit_log (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    tenant_id BIGINT REFERENCES ai_tenant(id),
    api_key_id BIGINT REFERENCES ai_tenant_api_key(id),
    actor VARCHAR(255),
    actor_ip VARCHAR(50),
    details JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_audit_tenant ON api_audit_log(tenant_id);
CREATE INDEX IF NOT EXISTS idx_audit_key ON api_audit_log(api_key_id);
CREATE INDEX IF NOT EXISTS idx_audit_type ON api_audit_log(event_type);
CREATE INDEX IF NOT EXISTS idx_audit_time ON api_audit_log(created_at);
