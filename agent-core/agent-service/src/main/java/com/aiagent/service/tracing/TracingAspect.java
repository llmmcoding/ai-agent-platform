package com.aiagent.service.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 链路追踪 AOP 切面
 * 自动追踪关键方法的执行
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class TracingAspect {

    private final Tracer tracer;

    /**
     * 追踪 LLM 调用
     */
    @Around("execution(* com.aiagent.service.LLMService.call(..))")
    public Object traceLLMCall(ProceedingJoinPoint joinPoint) throws Throwable {
        return traceMethod(joinPoint, "llm_call", span -> {
            // 设置 LLM 相关属性
            if (joinPoint.getArgs().length > 0) {
                span.setAttribute("llm.prompt_length", joinPoint.getArgs()[0].toString().length());
            }
        });
    }

    /**
     * 追踪 RAG 检索
     */
    @Around("execution(* com.aiagent.service.MemoryService.getLongTermMemory(..))")
    public Object traceRAGRetrieval(ProceedingJoinPoint joinPoint) throws Throwable {
        return traceMethod(joinPoint, "rag_retrieval", span -> {
            // 设置 RAG 相关属性
            Object[] args = joinPoint.getArgs();
            if (args.length > 0) {
                span.setAttribute("rag.user_id", args[0] != null ? args[0].toString() : "unknown");
            }
            if (args.length > 1) {
                span.setAttribute("rag.query_length", args[1].toString().length());
            }
        });
    }

    /**
     * 追踪向量检索
     */
    @Around("execution(* com.aiagent.service.milvus.MilvusService.searchVectors(..)) || " +
            "execution(* com.aiagent.service.pgvector.PGVectorService.searchVectors(..))")
    public Object traceVectorSearch(ProceedingJoinPoint joinPoint) throws Throwable {
        return traceMethod(joinPoint, "vector_search", span -> {
            Object[] args = joinPoint.getArgs();
            if (args.length > 1) {
                span.setAttribute("rag.collection", args[1].toString());
            }
            span.setAttribute("rag.provider", joinPoint.getTarget().getClass().getSimpleName());
        });
    }

    /**
     * 追踪工具执行
     */
    @Around("execution(* com.aiagent.service.ToolRegistry.execute(..))")
    public Object traceToolExecution(ProceedingJoinPoint joinPoint) throws Throwable {
        return traceMethod(joinPoint, "tool_execution", span -> {
            Object[] args = joinPoint.getArgs();
            if (args.length > 0) {
                span.setAttribute("tool.name", args[0] != null ? args[0].toString() : "unknown");
            }
        });
    }

    /**
     * 追踪 Agent 请求
     */
    @Around("execution(* com.aiagent.service.impl.AgentServiceImpl.invoke(..))")
    public Object traceAgentInvoke(ProceedingJoinPoint joinPoint) throws Throwable {
        return traceMethod(joinPoint, "agent_invoke", span -> {
            Object[] args = joinPoint.getArgs();
            if (args.length > 0) {
                Object request = args[0];
                try {
                    Method getUserId = request.getClass().getMethod("getUserId");
                    Object userId = getUserId.invoke(request);
                    span.setAttribute("agent.user_id", userId != null ? userId.toString() : "anonymous");
                } catch (Exception e) {
                    // ignore
                }
            }
        });
    }

    /**
     * 通用方法追踪
     */
    private Object traceMethod(ProceedingJoinPoint joinPoint, String spanName,
                              java.util.function.Consumer<Span> attributeSetter) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();

        long startTime = System.currentTimeMillis();

        try (Scope scope = span.makeCurrent()) {
            attributeSetter.accept(span);

            Object result = joinPoint.proceed();

            long duration = System.currentTimeMillis() - startTime;
            span.setAttribute("duration_ms", duration);
            span.setStatus(StatusCode.OK);

            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            span.setAttribute("duration_ms", duration);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
