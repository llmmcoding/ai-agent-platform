package com.aiagent.service.streaming;

import com.aiagent.common.Constants;
import com.aiagent.common.dto.AgentRequest;
import com.aiagent.service.metrics.AgentMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 流式 LLM 服务实现
 * 支持 OpenAI/Anthropic/Azure 等 Provider 的真流式调用
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamingLLMServiceImpl implements StreamingLLMService {

    private final WebClient webClient;
    private final AgentMetrics agentMetrics;

    @Value("${aiagent.llm.default-provider:openai}")
    private String defaultProvider;

    @Value("${aiagent.llm.request-timeout:120}")
    private int defaultTimeout;

    // OpenAI 配置
    @Value("${aiagent.llm.providers.openai.base-url:https://api.openai.com}")
    private String openaiBaseUrl;

    @Value("${aiagent.llm.providers.openai.model:gpt-4o}")
    private String openaiModel;

    @Value("${aiagent.llm.providers.openai.api-key:}")
    private String openaiApiKey;

    // Anthropic 配置
    @Value("${aiagent.llm.providers.anthropic.base-url:https://api.anthropic.com}")
    private String anthropicBaseUrl;

    @Value("${aiagent.llm.providers.anthropic.model:claude-3-5-sonnet-20241022}")
    private String anthropicModel;

    @Value("${aiagent.llm.providers.anthropic.api-key:}")
    private String anthropicApiKey;

    @Override
    public Flux<String> streamGenerate(String prompt, AgentRequest request) {
        String provider = request.getLlmProvider() != null ? request.getLlmProvider() : defaultProvider;

        long startTime = System.currentTimeMillis();
        AtomicLong tokenCount = new AtomicLong(0);

        Flux<String> tokenStream;
        if ("anthropic".equals(provider)) {
            tokenStream = streamAnthropic(prompt, request);
        } else {
            tokenStream = streamOpenAI(prompt, request);
        }

        return tokenStream
                .doOnNext(token -> tokenCount.incrementAndGet())
                .doOnComplete(() -> {
                    long latency = System.currentTimeMillis() - startTime;
                    log.debug("Stream completed: provider={}, tokens={}, latency={}ms",
                            provider, tokenCount.get(), latency);
                    if (agentMetrics != null) {
                        agentMetrics.recordLLMCall(latency);
                    }
                })
                .doOnError(e -> {
                    log.error("Stream error: provider={}, error={}", provider, e.getMessage());
                });
    }

    @Override
    public Flux<ServerSentEvent> streamGenerateSSE(String prompt, AgentRequest request) {
        return streamGenerate(prompt, request)
                .map(token -> ServerSentEvent.of(token))
                .concatWith(Flux.just(ServerSentEvent.done()))
                .onErrorResume(e -> Flux.just(ServerSentEvent.error(e.getMessage())));
    }

    /**
     * OpenAI 流式调用
     */
    private Flux<String> streamOpenAI(String prompt, AgentRequest request) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", openaiModel);
        requestBody.put("messages", new Object[]{
                Map.of("role", "user", "content", prompt)
        });
        requestBody.put("temperature", 0.7);
        requestBody.put("max_tokens", 4000);
        requestBody.put("stream", true);

        String uri = openaiBaseUrl + "/v1/chat/completions";

        return webClient.post()
                .uri(uri)
                .header("Authorization", "Bearer " + openaiApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(line -> line.startsWith("data: "))
                .map(line -> line.substring(6)) // Remove "data: " prefix
                .filter(data -> !"[DONE]".equals(data))
                .map(this::parseOpenAIStreamChunk);
    }

    /**
     * 解析 OpenAI 流式响应
     */
    private String parseOpenAIStreamChunk(String data) {
        try {
            // data 格式: {"id":"...","object":"chat.completion.chunk","created":...,"model":"...","choices":[{"index":0,"delta":{"content":"..."},"finish_reason":null}]}
            // 简化解析：提取 delta.content
            int contentIndex = data.indexOf("\"content\":\"");
            if (contentIndex > 0) {
                int start = contentIndex + 10;
                int end = data.indexOf("\"", start);
                if (end > start) {
                    return data.substring(start, end);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse OpenAI stream chunk: {}", data);
        }
        return "";
    }

    /**
     * Anthropic 流式调用
     */
    private Flux<String> streamAnthropic(String prompt, AgentRequest request) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", anthropicModel);
        requestBody.put("messages", new Object[]{
                Map.of("role", "user", "content", prompt)
        });
        requestBody.put("temperature", 0.7);
        requestBody.put("max_tokens", 4096);
        requestBody.put("stream", true);

        return webClient.post()
                .uri(anthropicBaseUrl + "/v1/messages")
                .header("x-api-key", anthropicApiKey)
                .header("anthropic-version", "2023-06-01")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(line -> line.startsWith("data: "))
                .map(line -> line.substring(6))
                .filter(data -> !"[DONE]".equals(data))
                .map(this::parseAnthropicStreamChunk);
    }

    /**
     * 解析 Anthropic 流式响应
     */
    private String parseAnthropicStreamChunk(String data) {
        try {
            // Anthropic 流式数据是 JSON lines 格式
            // {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"..."}}
            int textIndex = data.indexOf("\"text\":\"");
            if (textIndex > 0) {
                int start = textIndex + 8;
                int end = data.indexOf("\"", start);
                if (end > start) {
                    return data.substring(start, end);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse Anthropic stream chunk: {}", data);
        }
        return "";
    }
}
