package com.aiagent.service.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;

/**
 * 请求体大小限制过滤器
 * 防止过大请求体导致的 DoS 攻击
 */
@Slf4j
@Component
public class RequestSizeFilter implements WebFilter {

    @Value("${aiagent.security.max-request-size:10485760}")  // 10MB default
    private long maxRequestSize;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // 仅对 POST/PUT/PATCH 请求检查
        String method = exchange.getRequest().getMethod().name();
        if (!("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method))) {
            return chain.filter(exchange);
        }

        // 检查 Content-Length header
        long contentLength = exchange.getRequest().getHeaders().getContentLength();
        if (contentLength > maxRequestSize) {
            log.warn("Request body size {} exceeds limit {} for path: {}",
                    contentLength, maxRequestSize, exchange.getRequest().getPath().value());
            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            exchange.getResponse().getHeaders().add("X-Error-Code", "REQUEST_TOO_LARGE");
            byte[] message = ("Request body too large. Maximum size: " + maxRequestSize + " bytes").getBytes();
            return exchange.getResponse().writeWith(
                    Mono.just(exchange.getResponse().bufferFactory().wrap(message))
            );
        }

        // 如果没有 Content-Length，使用流式检查
        if (contentLength < 0) {
            return chain.filter(exchange);
        }

        return chain.filter(exchange);
    }
}
