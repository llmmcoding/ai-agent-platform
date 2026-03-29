package com.aiagent.service.ratelimit;

import com.aiagent.common.dto.TenantContext;
import com.aiagent.service.tenant.TenantContextHolder;
import com.aiagent.service.tenant.TenantRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 限流拦截器 - 将 TenantRateLimiter 集成到请求处理链
 * 在 API 请求真正处理之前检查限流
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class RateLimitInterceptor implements WebFilter {

    private final TenantRateLimiter rateLimiter;

    @Autowired
    public RateLimitInterceptor(@Autowired(required = false) TenantRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // 仅对 /api/v1/agent/* 路径限流
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/api/v1/agent")) {
            return chain.filter(exchange);
        }

        // 如果没有限流器，跳过
        if (rateLimiter == null) {
            return chain.filter(exchange);
        }

        // 获取租户上下文
        TenantContext context = TenantContextHolder.getContext();
        if (context == null) {
            // 无租户上下文，可能是公共端点或内部调用，跳过限流
            return chain.filter(exchange);
        }

        // 估算 token 数量
        int tokenCount = estimateTokenCount(exchange);

        // 检查限流
        TenantRateLimiter.RateLimitResult result = rateLimiter.checkRateLimit(context, tokenCount);

        if (!result.allowed()) {
            log.warn("Rate limit exceeded: type={}, limit={}, scope={}, tenant={}, path={}",
                    result.limitType(), result.limit(), result.scope(),
                    context.getTenantId(), path);

            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

            String body = String.format(
                    "{\"code\":429,\"message\":\"Rate limit exceeded: %s\",\"data\":null}",
                    result.limitType());

            return exchange.getResponse().writeWith(
                    Mono.just(exchange.getResponse().bufferFactory()
                            .wrap(body.getBytes(StandardCharsets.UTF_8))));
        }

        // 添加限流信息到 header
        exchange.getResponse().getHeaders().add("X-RateLimit-Type",
                result.limitType() != null ? result.limitType() : "none");
        exchange.getResponse().getHeaders().add("X-RateLimit-Limit",
                result.limit() != null ? result.limit().toString() : "unlimited");

        return chain.filter(exchange);
    }

    /**
     * 估算请求的 token 数量
     * 简化处理：从请求体大小估算（1字符 ≈ 1 token）
     */
    private int estimateTokenCount(ServerWebExchange exchange) {
        String contentLength = exchange.getRequest().getHeaders().getFirst("Content-Length");
        if (contentLength != null) {
            try {
                return Integer.parseInt(contentLength);
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return 100; // 默认估算
    }
}
