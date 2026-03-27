package com.aiagent.common.exception;

/**
 * AI Agent 平台基础异常
 */
public class AgentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private int code = 500;

    public AgentException() {
        super();
    }

    public AgentException(String message) {
        super(message);
    }

    public AgentException(String message, Throwable cause) {
        super(message, cause);
    }

    public AgentException(int code, String message) {
        super(message);
        this.code = code;
    }

    public AgentException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }
}
