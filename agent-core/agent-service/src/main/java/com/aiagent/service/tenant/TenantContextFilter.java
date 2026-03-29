package com.aiagent.service.tenant;

import com.aiagent.common.dto.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 租户上下文过滤器
 * 从请求头提取 API Key，验证并设置租户上下文
 */
@Slf4j
@Component
@Order(100) // 在其他过滤器之前执行
@RequiredArgsConstructor
public class TenantContextFilter implements WebFilter {

    private final TenantApiKeyService tenantApiKeyService;
    private final ObjectMapper objectMapper;

    // 需要 API Key 验证的路径
    private static final Set<String> PROTECTED_PATHS = Set.of(
            "/api/v1/agent",
            "/api/v1/keys"
    );

    // 跳过验证的路径
    private static final Set<String> SKIP_PATHS = Set.of(
            "/health",
            "/ready",
            "/swagger",
            "/v3/api-docs",
            "/prometheus"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        // 1. 检查是否需要验证
        if (!requiresAuthentication(path)) {
            return chain.filter(exchange);
        }

        // 2. 提取 API Key
        String apiKey = extractApiKey(request);
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("Missing API Key for path: {}", path);
            return writeError(exchange, HttpStatus.UNAUTHORIZED, "Missing API Key");
        }

        // 3. 验证 API Key
        TenantContext context = tenantApiKeyService.validateApiKey(apiKey);
        if (context == null) {
            log.warn("Invalid API Key for path: {}", path);
            return writeError(exchange, HttpStatus.UNAUTHORIZED, "Invalid API Key");
        }

        // 4. 检查工具权限
        if (path.startsWith("/api/v1/agent/")) {
            String method = request.getMethod().name();
            // 对于 agent 调用，检查是否需要特定工具
            // 允许继续，具体工具权限在 ToolRegistry 执行时检查
        }

        // 5. 设置上下文到 ThreadLocal
        TenantContextHolder.setContext(context);

        // 6. 添加响应头
        exchange.getResponse().getHeaders().add("X-Tenant-ID", context.getTenantId().toString());
        exchange.getResponse().getHeaders().add("X-Tenant-Code", context.getTenantCode());

        log.debug("Tenant context set: tenantId={}, userId={}, path={}",
                context.getTenantId(), context.getUserId(), path);

        // 7. 继续过滤器链，finally 中清除上下文
        return chain.filter(exchange)
                .doFinally(signal -> {
                    TenantContextHolder.clear();
                    log.debug("Tenant context cleared for path: {}", path);
                });
    }

    /**
     * 检查路径是否需要认证
     */
    private boolean requiresAuthentication(String path) {
        // 跳过非保护路径
        for (String skip : SKIP_PATHS) {
            if (path.startsWith(skip)) {
                return false;
            }
        }
        // 检查是否在保护路径内
        for (String protectedPath : PROTECTED_PATHS) {
            if (path.startsWith(protectedPath)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从请求提取 API Key
     */
    private String extractApiKey(ServerHttpRequest request) {
        // 1. 优先从 X-API-Key header
        String apiKey = request.getHeaders().getFirst("X-API-Key");
        if (apiKey != null && !apiKey.isEmpty()) {
            return apiKey;
        }

        // 2. 从 Authorization Bearer token
        String auth = request.getHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7);
        }

        // 3. 从 query parameter
        return request.getQueryParams().getFirst("api_key");
    }

    /**
     * 写入错误响应
     */
    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        try {
            String body = objectMapper.writeValueAsString(
                    java.util.Map.of(
                            "error", status.getReasonPhrase(),
                            "message", message,
                            "status", status.value()
                    )
            );
            DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
            return response.writeWith(Mono.just(buffer));
        } catch (Exception e) {
            byte[] body = ("{\"error\":\"" + status.getReasonPhrase() + "\",\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
            DataBuffer buffer = response.bufferFactory().wrap(body);
            return response.writeWith(Mono.just(buffer));
        }
    }
}
