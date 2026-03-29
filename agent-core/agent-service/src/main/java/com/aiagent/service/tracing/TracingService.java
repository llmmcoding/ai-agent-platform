package com.aiagent.service.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * 链路追踪服务
 * 提供 RAG 全链路追踪能力
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TracingService {

    private final Tracer tracer;

    private static final AttributeKey<String> COLLECTION_KEY = AttributeKey.stringKey("rag.collection");
    private static final AttributeKey<String> PROVIDER_KEY = AttributeKey.stringKey("rag.provider");
    private static final AttributeKey<String> MODEL_KEY = AttributeKey.stringKey("llm.model");
    private static final AttributeKey<String> TOOL_NAME_KEY = AttributeKey.stringKey("tool.name");
    private static final AttributeKey<Long> LATENCY_MS_KEY = AttributeKey.longKey("latency.ms");

    /**
     * 追踪 Embedding 操作
     */
    public <T> T traceEmbedding(String provider, Supplier<T> operation) {
        Span span = tracer.spanBuilder("embedding")
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        span.setAttribute(PROVIDER_KEY, provider);

        long startTime = System.currentTimeMillis();
        try (Scope scope = span.makeCurrent()) {
            T result = operation.get();
            span.setStatus(StatusCode.OK);
            return result;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.setAttribute(LATENCY_MS_KEY, System.currentTimeMillis() - startTime);
            span.end();
        }
    }

    /**
     * 追踪向量检索操作
     */
    public <T> T traceVectorSearch(String collection, String provider, Supplier<T> operation) {
        Span span = tracer.spanBuilder("vector_search")
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        span.setAttribute(COLLECTION_KEY, collection);
        span.setAttribute(PROVIDER_KEY, provider);

        long startTime = System.currentTimeMillis();
        try (Scope scope = span.makeCurrent()) {
            T result = operation.get();
            span.setStatus(StatusCode.OK);
            return result;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.setAttribute(LATENCY_MS_KEY, System.currentTimeMillis() - startTime);
            span.end();
        }
    }

    /**
     * 追踪 Rerank 操作
     */
    public <T> T traceRerank(String provider, Supplier<T> operation) {
        Span span = tracer.spanBuilder("rerank")
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        span.setAttribute(PROVIDER_KEY, provider);

        long startTime = System.currentTimeMillis();
        try (Scope scope = span.makeCurrent()) {
            T result = operation.get();
            span.setStatus(StatusCode.OK);
            return result;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.setAttribute(LATENCY_MS_KEY, System.currentTimeMillis() - startTime);
            span.end();
        }
    }

    /**
     * 追踪 LLM 调用
     */
    public <T> T traceLLMCall(String model, Supplier<T> operation) {
        Span span = tracer.spanBuilder("llm_call")
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        span.setAttribute(MODEL_KEY, model);

        long startTime = System.currentTimeMillis();
        try (Scope scope = span.makeCurrent()) {
            T result = operation.get();
            span.setStatus(StatusCode.OK);
            return result;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.setAttribute(LATENCY_MS_KEY, System.currentTimeMillis() - startTime);
            span.end();
        }
    }

    /**
     * 追踪工具执行
     */
    public <T> T traceTool(String toolName, Supplier<T> operation) {
        Span span = tracer.spanBuilder("tool_execution")
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        span.setAttribute(TOOL_NAME_KEY, toolName);

        long startTime = System.currentTimeMillis();
        try (Scope scope = span.makeCurrent()) {
            T result = operation.get();
            span.setStatus(StatusCode.OK);
            return result;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.setAttribute(LATENCY_MS_KEY, System.currentTimeMillis() - startTime);
            span.end();
        }
    }

    /**
     * 追踪 RAG 完整流程
     */
    public <T> T traceRAGFlow(String collection, Supplier<T> operation) {
        Span span = tracer.spanBuilder("rag_flow")
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        span.setAttribute(COLLECTION_KEY, collection);

        long startTime = System.currentTimeMillis();
        try (Scope scope = span.makeCurrent()) {
            T result = operation.get();
            span.setStatus(StatusCode.OK);
            return result;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.setAttribute(LATENCY_MS_KEY, System.currentTimeMillis() - startTime);
            span.end();
        }
    }

    /**
     * 获取当前 Trace ID
     */
    public String getCurrentTraceId() {
        Span currentSpan = Span.current();
        if (currentSpan != null && currentSpan.getSpanContext().isValid()) {
            return currentSpan.getSpanContext().getTraceId();
        }
        return null;
    }

    /**
     * 获取当前 Span ID
     */
    public String getCurrentSpanId() {
        Span currentSpan = Span.current();
        if (currentSpan != null && currentSpan.getSpanContext().isValid()) {
            return currentSpan.getSpanContext().getSpanId();
        }
        return null;
    }

    /**
     * 添加 Span 事件
     */
    public void addEvent(String eventName) {
        Span currentSpan = Span.current();
        if (currentSpan != null) {
            currentSpan.addEvent(eventName);
        }
    }

    /**
     * 添加 Span 属性
     */
    public void setAttribute(String key, String value) {
        Span currentSpan = Span.current();
        if (currentSpan != null) {
            currentSpan.setAttribute(AttributeKey.stringKey(key), value);
        }
    }

    /**
     * 设置 Span 属性 (long)
     */
    public void setAttribute(String key, long value) {
        Span currentSpan = Span.current();
        if (currentSpan != null) {
            currentSpan.setAttribute(AttributeKey.longKey(key), value);
        }
    }

    /**
     * 记录成功
     */
    public void recordSuccess() {
        Span currentSpan = Span.current();
        if (currentSpan != null) {
            currentSpan.setStatus(StatusCode.OK);
        }
    }

    /**
     * 记录错误
     */
    public void recordError(Exception e) {
        Span currentSpan = Span.current();
        if (currentSpan != null) {
            currentSpan.setStatus(StatusCode.ERROR, e.getMessage());
            currentSpan.recordException(e);
        }
    }
}