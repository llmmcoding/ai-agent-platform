package com.aiagent.service.config;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

/**
 * WebClient 配置 - 针对高并发 AI Agent 场景优化
 * 借鉴 llmgateway 的连接池设计理念
 */
@Configuration
public class WebClientConfig {

    @Value("${aiagent.webclient.max-connections:1000}")
    private int maxConnections;

    @Value("${aiagent.webclient.per-route:100}")
    private int perRoute;

    @Value("${aiagent.webclient.connect-timeout:5000}")
    private long connectTimeout;

    @Value("${aiagent.webclient.response-timeout:120000}")
    private long responseTimeout;

    @Value("${aiagent.webclient.pending-acquire-timeout:10000}")
    private long pendingAcquireTimeout;

    @Bean
    public WebClient webClient() {
        // 配置连接提供器 - 借鉴 llmgateway 的高并发配置
        ConnectionProvider connectionProvider = ConnectionProvider.builder("ai-agent-pool")
                .maxConnections(maxConnections)
                .maxIdleTime(Duration.ofSeconds(30))
                .maxLifeTime(Duration.ofSeconds(60))
                .pendingAcquireTimeout(Duration.ofMillis(pendingAcquireTimeout))
                .pendingAcquireMaxCount(500)
                .evictInBackground(Duration.ofSeconds(30))
                .build();

        HttpClient httpClient = HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout)
                .responseTimeout(Duration.ofMillis(responseTimeout))
                // 禁用重定向以减少延迟
                .followRedirect(false);

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> {
                    configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024); // 16MB for streaming
                })
                .build();
    }
}
