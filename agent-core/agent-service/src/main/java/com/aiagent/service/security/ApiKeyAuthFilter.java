package com.aiagent.service.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * API Key 认证过滤器 (WebFlux版本)
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class ApiKeyAuthFilter implements WebFilter {

    private final SecurityConfig securityConfig;

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String API_KEY_PARAM = "api_key";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 跳过健康检查和swagger
        if (path.contains("/health") || path.contains("/ready") ||
            path.contains("/swagger") || path.contains("/v3/api-docs")) {
            return chain.filter(exchange);
        }

        // 如果未启用安全检查
        if (!securityConfig.isEnabled() || !securityConfig.isApiKeyEnabled()) {
            return chain.filter(exchange);
        }

        // 获取 API Key
        String apiKey = request.getHeaders().getFirst(API_KEY_HEADER);
        if (apiKey == null) {
            // 尝试从查询参数获取
            apiKey = request.getQueryParams().getFirst(API_KEY_PARAM);
        }

        // 验证 API Key
        if (!securityConfig.isValidApiKey(apiKey)) {
            log.warn("Invalid API Key from: {}", request.getRemoteAddress());
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            response.getHeaders().add("Content-Type", "application/json");
            return response.writeWith(Mono.just(response.bufferFactory()
                    .wrap("{\"error\": \"Unauthorized\", \"message\": \"Invalid or missing API Key\"}".getBytes())));
        }

        return chain.filter(exchange);
    }
}
