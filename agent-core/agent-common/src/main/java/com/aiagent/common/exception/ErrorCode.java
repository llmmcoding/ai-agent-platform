package com.aiagent.common.exception;

import lombok.Getter;

/**
 * 错误码定义
 */
@Getter
public enum ErrorCode {

    // 系统错误 (1000-1999)
    SYSTEM_ERROR(1000, "系统内部错误"),
    INVALID_PARAMETER(1001, "参数错误"),
    SERVICE_UNAVAILABLE(1002, "服务不可用"),
    TIMEOUT(1003, "请求超时"),

    // LLM 相关错误 (2000-2999)
    LLM_PROVIDER_ERROR(2000, "LLM 提供商错误"),
    LLM_INVALID_RESPONSE(2001, "LLM 响应无效"),
    LLM_TIMEOUT(2002, "LLM 调用超时"),
    LLM_RATE_LIMIT(2003, "LLM 限流"),
    LLM_MODEL_NOT_FOUND(2004, "LLM 模型未找到"),
    LLM_INVALID_API_KEY(2005, "LLM API Key 无效"),

    // Tool 相关错误 (3000-3999)
    TOOL_NOT_FOUND(3000, "工具未找到"),
    TOOL_EXECUTE_ERROR(3001, "工具执行错误"),
    TOOL_TIMEOUT(3002, "工具执行超时"),
    TOOL_INVALID_PARAMETER(3003, "工具参数错误"),

    // Memory 相关错误 (4000-4999)
    MEMORY_NOT_FOUND(4000, "记忆未找到"),
    MEMORY_SAVE_ERROR(4001, "记忆保存错误"),
    MEMORY_RETRIEVE_ERROR(4002, "记忆检索错误"),

    // RAG 相关错误 (5000-5999)
    RAG_VECTOR_ERROR(5000, "向量检索错误"),
    RAG_INDEX_ERROR(5001, "向量索引错误"),
    RAG_EMBEDDING_ERROR(5002, "Embedding 服务错误"),

    // 认证/授权错误 (6000-6999)
    UNAUTHORIZED(6000, "未授权"),
    FORBIDDEN(6001, "禁止访问"),
    TOKEN_EXPIRED(6002, "Token 过期"),

    // 限流错误 (7000-7999)
    RATE_LIMIT_EXCEEDED(7000, "请求频率超限"),
    QUOTA_EXCEEDED(7001, "配额超限");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
