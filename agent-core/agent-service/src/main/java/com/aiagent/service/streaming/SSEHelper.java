package com.aiagent.service.streaming;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * SSE (Server-Sent Events) 辅助类
 */
@Component
public class SSEHelper {

    /**
     * 将 token 流转换为 SSE 格式
     */
    public Flux<org.springframework.http.codec.ServerSentEvent<String>> toSSE(Flux<String> tokenStream) {
        return tokenStream
                .map(token -> org.springframework.http.codec.ServerSentEvent.<String>builder()
                        .event("message")
                        .data(token)
                        .build())
                .concatWith(Flux.defer(() -> Flux.just(
                        org.springframework.http.codec.ServerSentEvent.<String>builder()
                                .event("done")
                                .data("")
                                .build()
                )))
                .onErrorResume(e -> Flux.just(
                        org.springframework.http.codec.ServerSentEvent.<String>builder()
                                .event("error")
                                .data(e.getMessage())
                                .build()
                ));
    }

    /**
     * 创建心跳 SSE (保持连接)
     */
    public Flux<org.springframework.http.codec.ServerSentEvent<String>> heartbeat() {
        return Flux.interval(Duration.ofSeconds(30))
                .map(tick -> org.springframework.http.codec.ServerSentEvent.<String>builder()
                        .event("heartbeat")
                        .data("")
                        .build());
    }

    /**
     * SSE content type
     */
    public static final MediaType TEXT_EVENT_STREAM = MediaType.parseMediaType("text/event-stream");
}
