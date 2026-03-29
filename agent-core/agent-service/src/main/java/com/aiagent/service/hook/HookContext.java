package com.aiagent.service.hook;

import com.aiagent.common.dto.AgentRequest;
import com.aiagent.common.dto.AgentResponse;
import com.aiagent.service.memory.ContextCompactionService;
import com.aiagent.service.task.Task;

import java.util.HashMap;
import java.util.Map;

/**
 * 钩子上下文 - 存储钩子执行时的数据和状态
 * 扩展支持全生命周期 (借鉴 OpenClaw)
 */
public class HookContext {

    private final Map<String, Object> attributes = new HashMap<>();
    private AgentRequest request;
    private AgentResponse response;
    private String stage;
    private Throwable error;

    // 扩展字段 (用于不同生命周期)
    private String sessionId;
    private String toolName;
    private Map<String, Object> toolInput;
    private Object toolOutput;
    private String modelProvider;
    private String prompt;
    private String llmRequestBody;
    private String llmResponseBody;
    private Task task;
    private ContextCompactionService.CompactionResult compactionResult;

    public HookContext() {
    }

    public HookContext(AgentRequest request) {
        this.request = request;
    }

    // ==================== Getters & Setters ====================

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

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public Map<String, Object> getToolInput() {
        return toolInput;
    }

    public void setToolInput(Map<String, Object> toolInput) {
        this.toolInput = toolInput;
    }

    public Object getToolOutput() {
        return toolOutput;
    }

    public void setToolOutput(Object toolOutput) {
        this.toolOutput = toolOutput;
    }

    public String getModelProvider() {
        return modelProvider;
    }

    public void setModelProvider(String modelProvider) {
        this.modelProvider = modelProvider;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getLlmRequestBody() {
        return llmRequestBody;
    }

    public void setLlmRequestBody(String llmRequestBody) {
        this.llmRequestBody = llmRequestBody;
    }

    public String getLlmResponseBody() {
        return llmResponseBody;
    }

    public void setLlmResponseBody(String llmResponseBody) {
        this.llmResponseBody = llmResponseBody;
    }

    public Task getTask() {
        return task;
    }

    public void setTask(Task task) {
        this.task = task;
    }

    public ContextCompactionService.CompactionResult getCompactionResult() {
        return compactionResult;
    }

    public void setCompactionResult(ContextCompactionService.CompactionResult compactionResult) {
        this.compactionResult = compactionResult;
    }

    // ==================== Attribute Methods ====================

    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    public Map<String, Object> getAllAttributes() {
        return new HashMap<>(attributes);
    }

    // ==================== Factory Methods ====================

    /**
     * Gateway 启动
     */
    public static HookContext forGatewayStart() {
        HookContext ctx = new HookContext();
        ctx.setStage("GATEWAY_START");
        return ctx;
    }

    /**
     * Gateway 停止
     */
    public static HookContext forGatewayStop() {
        HookContext ctx = new HookContext();
        ctx.setStage("GATEWAY_STOP");
        return ctx;
    }

    /**
     * Session 开始
     */
    public static HookContext forSessionStart(String sessionId, AgentRequest request) {
        HookContext ctx = new HookContext(request);
        ctx.setSessionId(sessionId);
        ctx.setStage("SESSION_START");
        return ctx;
    }

    /**
     * Session 结束
     */
    public static HookContext forSessionEnd(String sessionId, AgentRequest request, AgentResponse response) {
        HookContext ctx = new HookContext(request);
        ctx.setSessionId(sessionId);
        ctx.setResponse(response);
        ctx.setStage("SESSION_END");
        return ctx;
    }

    /**
     * 预处理器
     */
    public static HookContext forPreProcess(AgentRequest request) {
        HookContext ctx = new HookContext(request);
        ctx.setStage("PRE_PROCESS");
        return ctx;
    }

    /**
     * 后处理器
     */
    public static HookContext forPostProcess(AgentRequest request, AgentResponse response) {
        HookContext ctx = new HookContext(request);
        ctx.setResponse(response);
        ctx.setStage("POST_PROCESS");
        return ctx;
    }

    /**
     * Agent 启动前
     */
    public static HookContext forBeforeAgentStart(AgentRequest request) {
        HookContext ctx = new HookContext(request);
        ctx.setStage("BEFORE_AGENT_START");
        return ctx;
    }

    /**
     * Agent 结束后
     */
    public static HookContext forAgentEnd(AgentRequest request, AgentResponse response) {
        HookContext ctx = new HookContext(request);
        ctx.setResponse(response);
        ctx.setStage("AGENT_END");
        return ctx;
    }

    /**
     * 模型解析前
     */
    public static HookContext forBeforeModelResolve(AgentRequest request, String modelProvider) {
        HookContext ctx = new HookContext(request);
        ctx.setModelProvider(modelProvider);
        ctx.setStage("BEFORE_MODEL_RESOLVE");
        return ctx;
    }

    /**
     * Prompt 构建前
     */
    public static HookContext forBeforePromptBuild(AgentRequest request, String prompt) {
        HookContext ctx = new HookContext(request);
        ctx.setPrompt(prompt);
        ctx.setStage("BEFORE_PROMPT_BUILD");
        return ctx;
    }

    /**
     * LLM 输入
     */
    public static HookContext forLlmInput(AgentRequest request, String modelProvider, String requestBody) {
        HookContext ctx = new HookContext(request);
        ctx.setModelProvider(modelProvider);
        ctx.setLlmRequestBody(requestBody);
        ctx.setStage("LLM_INPUT");
        return ctx;
    }

    /**
     * LLM 输出
     */
    public static HookContext forLlmOutput(AgentRequest request, String modelProvider, String responseBody) {
        HookContext ctx = new HookContext(request);
        ctx.setModelProvider(modelProvider);
        ctx.setLlmResponseBody(responseBody);
        ctx.setStage("LLM_OUTPUT");
        return ctx;
    }

    /**
     * 工具调用前
     */
    public static HookContext forBeforeToolCall(AgentRequest request, String toolName, Map<String, Object> input) {
        HookContext ctx = new HookContext(request);
        ctx.setToolName(toolName);
        ctx.setToolInput(input);
        ctx.setStage("BEFORE_TOOL_CALL");
        return ctx;
    }

    /**
     * 工具调用后
     */
    public static HookContext forAfterToolCall(AgentRequest request, String toolName,
                                                Map<String, Object> input, Object output) {
        HookContext ctx = new HookContext(request);
        ctx.setToolName(toolName);
        ctx.setToolInput(input);
        ctx.setToolOutput(output);
        ctx.setStage("AFTER_TOOL_CALL");
        return ctx;
    }

    /**
     * Subagent 创建中
     */
    public static HookContext forSubagentSpawning(AgentRequest request, String subagentId) {
        HookContext ctx = new HookContext(request);
        ctx.setSessionId(subagentId);
        ctx.setStage("SUBAGENT_SPAWNING");
        return ctx;
    }

    /**
     * Subagent 已创建
     */
    public static HookContext forSubagentSpawned(AgentRequest request, String subagentId) {
        HookContext ctx = new HookContext(request);
        ctx.setSessionId(subagentId);
        ctx.setStage("SUBAGENT_SPAWNED");
        return ctx;
    }

    /**
     * Subagent 已结束
     */
    public static HookContext forSubagentEnded(AgentRequest request, String subagentId, AgentResponse response) {
        HookContext ctx = new HookContext(request);
        ctx.setSessionId(subagentId);
        ctx.setResponse(response);
        ctx.setStage("SUBAGENT_ENDED");
        return ctx;
    }

    /**
     * 压缩前
     */
    public static HookContext forBeforeCompaction(AgentRequest request, String sessionId) {
        HookContext ctx = new HookContext(request);
        ctx.setSessionId(sessionId);
        ctx.setStage("BEFORE_COMPACTION");
        return ctx;
    }

    /**
     * 压缩后
     */
    public static HookContext forAfterCompaction(AgentRequest request, String sessionId,
                                                  ContextCompactionService.CompactionResult result) {
        HookContext ctx = new HookContext(request);
        ctx.setSessionId(sessionId);
        ctx.setCompactionResult(result);
        ctx.setStage("AFTER_COMPACTION");
        return ctx;
    }

    /**
     * 任务创建
     */
    public static HookContext forTaskCreated(AgentRequest request, Task task) {
        HookContext ctx = new HookContext(request);
        ctx.setTask(task);
        ctx.setStage("TASK_CREATED");
        return ctx;
    }

    /**
     * 任务完成
     */
    public static HookContext forTaskCompleted(AgentRequest request, Task task) {
        HookContext ctx = new HookContext(request);
        ctx.setTask(task);
        ctx.setStage("TASK_COMPLETED");
        return ctx;
    }

    /**
     * RAG 查询前
     */
    public static HookContext forBeforeRagQuery(AgentRequest request, String query) {
        HookContext ctx = new HookContext(request);
        ctx.setPrompt(query);  // 复用 prompt 字段存储 query
        ctx.setStage("BEFORE_RAG_QUERY");
        return ctx;
    }

    /**
     * RAG 查询后
     */
    public static HookContext forAfterRagQuery(AgentRequest request, String query, String results) {
        HookContext ctx = new HookContext(request);
        ctx.setPrompt(query);
        ctx.setLlmResponseBody(results);  // 复用字段存储结果
        ctx.setStage("AFTER_RAG_QUERY");
        return ctx;
    }

    /**
     * 错误处理
     */
    public static HookContext forError(AgentRequest request, Throwable error) {
        HookContext ctx = new HookContext(request);
        ctx.setError(error);
        ctx.setStage("ERROR");
        return ctx;
    }
}
