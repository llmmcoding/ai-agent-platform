package com.aiagent.service.hook;

import com.aiagent.common.dto.AgentRequest;
import com.aiagent.common.dto.AgentResponse;

import java.util.HashMap;
import java.util.Map;

/**
 * 钩子上下文 - 存储钩子执行时的数据和状态
 */
public class HookContext {

    private final Map<String, Object> attributes = new HashMap<>();
    private AgentRequest request;
    private AgentResponse response;
    private String stage;
    private Throwable error;

    public HookContext() {
    }

    public HookContext(AgentRequest request) {
        this.request = request;
    }

    public AgentRequest getRequest() {
        return request;
    }

    public void setRequest(AgentRequest request) {
        this.request = request;
    }

    public AgentResponse getResponse() {
        return response;
    }

    public void setResponse(AgentResponse response) {
        this.response = response;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public Throwable getError() {
        return error;
    }

    public void setError(Throwable error) {
        this.error = error;
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    public Map<String, Object> getAllAttributes() {
        return new HashMap<>(attributes);
    }

    /**
     * 创建预处理器上下文
     */
    public static HookContext forPreProcess(AgentRequest request) {
        HookContext ctx = new HookContext(request);
        ctx.setStage("PRE_PROCESS");
        return ctx;
    }

    /**
     * 创建后处理器上下文
     */
    public static HookContext forPostProcess(AgentRequest request, AgentResponse response) {
        HookContext ctx = new HookContext(request);
        ctx.setResponse(response);
        ctx.setStage("POST_PROCESS");
        return ctx;
    }

    /**
     * 创建错误处理上下文
     */
    public static HookContext forError(AgentRequest request, Throwable error) {
        HookContext ctx = new HookContext(request);
        ctx.setError(error);
        ctx.setStage("ERROR");
        return ctx;
    }
}
