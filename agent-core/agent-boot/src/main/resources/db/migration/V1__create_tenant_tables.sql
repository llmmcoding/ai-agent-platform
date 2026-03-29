-- 多租户架构数据库迁移
-- V1: 创建租户表和 API Key 表

-- ============================================
-- 租户表
-- ============================================
CREATE TABLE IF NOT EXISTS ai_tenant (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    code VARCHAR(100) NOT NULL UNIQUE,
    rpm_limit BIGINT DEFAULT 0,
    tpm_limit BIGINT DEFAULT 0,
    max_concurrent_requests INT DEFAULT 10,
    metadata JSONB DEFAULT '{}',
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_tenant_code ON ai_tenant(code);
CREATE INDEX IF NOT EXISTS idx_tenant_active ON ai_tenant(is_active);

-- ============================================
-- 租户 API Key 表
-- ============================================
CREATE TABLE IF NOT EXISTS ai_tenant_api_key (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES ai_tenant(id) ON DELETE CASCADE,
    key_hash VARCHAR(255) NOT NULL UNIQUE,
    key_alias VARCHAR(100) NOT NULL,
    user_id VARCHAR(255),
    rpm_limit BIGINT DEFAULT 0,          -- 0 = 无限制
    tpm_limit BIGINT DEFAULT 0,          -- 0 = 无限制
    allowed_tools VARCHAR(1000),         -- 逗号分隔，空 = 允许所有工具
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP,                -- NULL = 永久有效
    CONSTRAINT chk_expires_future CHECK (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
);

CREATE INDEX IF NOT EXISTS idx_apikey_tenant ON ai_tenant_api_key(tenant_id);
CREATE INDEX IF NOT EXISTS idx_apikey_hash ON ai_tenant_api_key(key_hash);
CREATE INDEX IF NOT EXISTS idx_apikey_active ON ai_tenant_api_key(is_active);

-- ============================================
-- 平台管理员 Key 表 (用于管理租户)
-- ============================================
CREATE TABLE IF NOT EXISTS ai_platform_api_key (
    id BIGSERIAL PRIMARY KEY,
    key_hash VARCHAR(255) NOT NULL UNIQUE,
    key_alias VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,          -- 管理员名称
    permissions VARCHAR(500) DEFAULT 'tenant:*,key:*',  -- 权限范围
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP                 -- NULL = 永久有效
);

CREATE INDEX IF NOT EXISTS idx_platformkey_hash ON ai_platform_api_key(key_hash);
CREATE INDEX IF NOT EXISTS idx_platformkey_active ON ai_platform_api_key(is_active);

-- ============================================
-- 插入默认平台管理员 Key
-- 生成: sk-platform-xK9mP2vL7qR4tY8wE1jH3nM5cB6gF0d
-- 哈希: sha256('sk-platform-xK9mP2vL7qR4tY8wE1jH3nM5cB6gF0d')
-- ============================================
INSERT INTO ai_platform_api_key (key_hash, key_alias, name, permissions, is_active)
VALUES (
    '7f7c8e8d3e9a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c',
    'sk-platform-xxxx1234',
    'Platform Admin',
    'tenant:*,key:*,admin:*',
    TRUE
) ON CONFLICT (key_hash) DO NOTHING;
