package com.aiagent.service.impl;

import com.aiagent.common.dto.AgentRequest;
import com.aiagent.common.dto.AgentResponse;
import com.aiagent.common.Constants;
import com.aiagent.common.exception.AgentException;
import com.aiagent.common.exception.ErrorCode;
import com.aiagent.service.LLMService;
import com.aiagent.service.cache.LLMCache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 服务实现 - 支持多 Provider (OpenAI / Anthropic / Azure / 本地模型)
 * 借鉴 llmgateway 的多 Provider 路由设计，但保持精简
 */
@Slf4j
@Service
public class LLMServiceImpl implements LLMService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final LLMCache llmCache;

    @Value("${aiagent.llm.default-provider:openai}")
    private String defaultProvider;

    @Value("${aiagent.llm.router.strategy:failover}")
    private String routerStrategy;

    @Value("${aiagent.llm.router.max-retries:2}")
    private int maxRetries;

    @Value("${aiagent.llm.request-timeout:120}")
    private int defaultTimeout;

    /**
     * Provider 配置
     */
    private final Map<String, LLMProviderConfig> providerConfigs = new ConcurrentHashMap<>();

    public LLMServiceImpl(ObjectMapper objectMapper, WebClient webClient, LLMCache llmCache) {
        this.objectMapper = objectMapper;
        this.webClient = webClient;
        this.llmCache = llmCache;
    }

    @PostConstruct
    public void init() {
        // 初始化 Provider 配置
        // OpenAI
        String openaiKey = System.getenv("OPENAI_API_KEY");
        if (openaiKey != null && !openaiKey.isEmpty()) {
            providerConfigs.put(Constants.LLM.OPENAI, LLMProviderConfig.builder()
                    .name(Constants.LLM.OPENAI)
                    .baseUrl(getConfigOrDefault("OPENAI_BASE_URL", "https://api.openai.com"))
                    .model(getConfigOrDefault("OPENAI_MODEL", "gpt-4o"))
                    .apiKey(openaiKey)
                    .timeout(defaultTimeout)
                    .maxTokens(4000)
                    .temperature(0.7)
                    .build());
        }

        // Anthropic
        String anthropicKey = System.getenv("ANTHROPIC_API_KEY");
        if (anthropicKey != null && !anthropicKey.isEmpty()) {
            providerConfigs.put(Constants.LLM.ANTHROPIC, LLMProviderConfig.builder()
                    .name(Constants.LLM.ANTHROPIC)
                    .baseUrl(getConfigOrDefault("ANTHROPIC_BASE_URL", "https://api.anthropic.com"))
                    .model(getConfigOrDefault("ANTHROPIC_MODEL", "claude-3-5-sonnet-20241022"))
                    .apiKey(anthropicKey)
                    .timeout(defaultTimeout)
                    .maxTokens(4000)
                    .temperature(0.7)
                    .build());
        }

        // Azure OpenAI (借鉴 llmgateway)
        String azureKey = System.getenv("AZURE_OPENAI_KEY");
        String azureEndpoint = System.getenv("AZURE_OPENAI_ENDPOINT");
        if (azureKey != null && azureEndpoint != null) {
            providerConfigs.put("azure", LLMProviderConfig.builder()
                    .name("azure")
                    .baseUrl(azureEndpoint)
                    .model(System.getenv("AZURE_OPENAI_DEPLOYMENT"))
                    .apiKey(azureKey)
                    .timeout(defaultTimeout)
                    .maxTokens(4000)
                    .temperature(0.7)
                    .isAzure(true)
                    .apiVersion("2024-02-01")
                    .build());
        }

        // 本地模型支持 (Ollama 等)
        String localUrl = System.getenv("LOCAL_LLM_URL");
        if (localUrl != null && !localUrl.isEmpty()) {
            providerConfigs.put("local", LLMProviderConfig.builder()
                    .name("local")
                    .baseUrl(localUrl)
                    .model(getConfigOrDefault("LOCAL_LLM_MODEL", "qwen2.5"))
                    .apiKey("ollama") // Ollama 不需要 API Key
                    .timeout(defaultTimeout)
                    .maxTokens(2000)
                    .temperature(0.7)
                    .isLocal(true)
                    .build());
        }

        log.info("LLM Service initialized with {} providers: {}", providerConfigs.size(), providerConfigs.keySet());
    }

    private String getConfigOrDefault(String envKey, String defaultValue) {
        String value = System.getenv(envKey);
        return value != null && !value.isEmpty() ? value : defaultValue;
    }

    @Override
    public AgentResponse call(String prompt, AgentRequest request) {
        String provider = selectProvider(request);
        LLMProviderConfig config = providerConfigs.get(provider);

        if (config == null || config.getApiKey() == null) {
            throw new AgentException(ErrorCode.LLM_PROVIDER_ERROR.getCode(),
                    "LLM provider not configured: " + provider);
        }

        // 1. 检查缓存 (仅对非流式请求缓存)
        if (!Boolean.TRUE.equals(request.getParameters().get("stream"))) {
            String cachedResponse = llmCache.get(prompt, provider, config.getModel());
            if (cachedResponse != null) {
                log.debug("Using cached LLM response");
                return AgentResponse.builder()
                        .content(cachedResponse)
                        .llmProvider(provider)
                        .completed(true)
                        .status("COMPLETED")
                        .metadata(Map.of("cached", true))
                        .build();
            }
        }

        // 2. 调用 LLM
        int retries = 0;
        Exception lastException = null;

        while (retries <= maxRetries) {
            try {
                long startTime = System.currentTimeMillis();
                AgentResponse response = executeLLMCall(prompt, config, request);

                // 记录延迟
                response.setLatencyMs(System.currentTimeMillis() - startTime);

                // 3. 存储到缓存
                if (!Boolean.TRUE.equals(request.getParameters().get("stream"))) {
                    llmCache.put(prompt, provider, config.getModel(), response.getContent());
                }

                return response;
            } catch (AgentException e) {
                if (e.getCode() == ErrorCode.LLM_RATE_LIMIT.getCode() && retries < maxRetries) {
                    retries++;
                    log.warn("Rate limit hit, retrying ({}/{})", retries, maxRetries);
                    try {
                        Thread.sleep(1000L * retries);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    continue;
                }
                throw e;
            } catch (Exception e) {
                lastException = e;
                if (retries < maxRetries) {
                    retries++;
                    log.warn("LLM call failed, retrying ({}/{}): {}", retries, maxRetries, e.getMessage());
                    continue;
                }
            }
        }

        throw new AgentException(ErrorCode.LLM_TIMEOUT.getCode(),
                "LLM call failed after " + maxRetries + " retries", lastException);
    }

    private AgentResponse executeLLMCall(String prompt, LLMProviderConfig config, AgentRequest request) {
        try {
            if ("azure".equals(config.getName())) {
                return callAzureOpenAI(prompt, config, request);
            } else if (config.isLocal()) {
                return callLocalLLM(prompt, config, request);
            } else if (Constants.LLM.OPENAI.equals(config.getName())) {
                return callOpenAI(prompt, config, request);
            } else if (Constants.LLM.ANTHROPIC.equals(config.getName())) {
                return callAnthropic(prompt, config, request);
            } else {
                throw new AgentException(ErrorCode.LLM_MODEL_NOT_FOUND.getCode(),
                        "Unsupported LLM provider: " + config.getName());
            }
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 429) {
                throw new AgentException(ErrorCode.LLM_RATE_LIMIT.getCode(), "Rate limit exceeded");
            } else if (e.getStatusCode().value() == 401) {
                throw new AgentException(ErrorCode.LLM_INVALID_API_KEY.getCode(), "Invalid API key");
            }
            throw new AgentException(ErrorCode.LLM_PROVIDER_ERROR.getCode(),
                    "LLM API error: " + e.getResponseBodyAsString());
        }
    }

    private AgentResponse callOpenAI(String prompt, LLMProviderConfig config, AgentRequest request) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", config.getModel());
        requestBody.put("messages", new Object[]{
                Map.of("role", "user", "content", prompt)
        });
        requestBody.put("temperature", config.getTemperature());
        requestBody.put("max_tokens", config.getMaxTokens());

        Map response = webClient.post()
                .uri(config.getBaseUrl() + "/v1/chat/completions")
                .header("Authorization", "Bearer " + config.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofSeconds(config.getTimeout()));

        return parseOpenAIResponse(response, config.getName());
    }

    private AgentResponse parseOpenAIResponse(Map response, String provider) {
        try {
            Map<String, Object> choice = (Map<String, Object>)
                    ((java.util.List) response.get("choices")).get(0);
            Map<String, Object> message = (Map<String, Object>) choice.get("message");

            Map<String, Object> usage = (Map<String, Object>) response.get("usage");

            return AgentResponse.builder()
                    .content((String) message.get("content"))
                    .llmProvider(provider)
                    .completed(true)
                    .status("COMPLETED")
                    .tokenUsage(AgentResponse.TokenUsage.builder()
                            .promptTokens((Integer) usage.get("prompt_tokens"))
                            .completionTokens((Integer) usage.get("completion_tokens"))
                            .totalTokens((Integer) usage.get("total_tokens"))
                            .build())
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse OpenAI response: {}", response);
            throw new AgentException(ErrorCode.LLM_INVALID_RESPONSE.getCode(),
                    "Failed to parse LLM response", e);
        }
    }

    private AgentResponse callAnthropic(String prompt, LLMProviderConfig config, AgentRequest request) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", config.getModel());
        requestBody.put("messages", new Object[]{
                Map.of("role", "user", "content", prompt)
        });
        requestBody.put("temperature", config.getTemperature());
        requestBody.put("max_tokens", config.getMaxTokens());

        Map response = webClient.post()
                .uri(config.getBaseUrl() + "/v1/messages")
                .header("x-api-key", config.getApiKey())
                .header("anthropic-version", "2023-06-01")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofSeconds(config.getTimeout()));

        return parseAnthropicResponse(response, config.getName());
    }

    private AgentResponse parseAnthropicResponse(Map response, String provider) {
        try {
            Map<String, Object> content = (Map<String, Object>)
                    ((java.util.List) response.get("content")).get(0);

            Map<String, Object> usage = (Map<String, Object>) response.get("usage");

            return AgentResponse.builder()
                    .content((String) content.get("text"))
                    .llmProvider(provider)
                    .completed(true)
                    .status("COMPLETED")
                    .tokenUsage(AgentResponse.TokenUsage.builder()
                            .promptTokens((Integer) usage.get("input_tokens"))
                            .completionTokens((Integer) usage.get("output_tokens"))
                            .totalTokens(((Integer) usage.get("input_tokens")) +
                                    ((Integer) usage.get("output_tokens")))
                            .build())
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse Anthropic response: {}", response);
            throw new AgentException(ErrorCode.LLM_INVALID_RESPONSE.getCode(),
                    "Failed to parse LLM response", e);
        }
    }

    /**
     * Azure OpenAI 调用 (借鉴 llmgateway 的 Azure 支持)
     */
    private AgentResponse callAzureOpenAI(String prompt, LLMProviderConfig config, AgentRequest request) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("messages", new Object[]{
                Map.of("role", "user", "content", prompt)
        });
        requestBody.put("temperature", config.getTemperature());
        requestBody.put("max_tokens", config.getMaxTokens());

        String url = String.format("%s/openai/deployments/%s/chat/completions?api-version=%s",
                config.getBaseUrl(), config.getModel(), config.getApiVersion());

        Map response = webClient.post()
                .uri(url)
                .header("api-key", config.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofSeconds(config.getTimeout()));

        return parseOpenAIResponse(response, config.getName());
    }

    /**
     * 本地模型调用 (Ollama 等)
     */
    private AgentResponse callLocalLLM(String prompt, LLMProviderConfig config, AgentRequest request) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", config.getModel());
        requestBody.put("prompt", prompt);
        requestBody.put("temperature", config.getTemperature());
        requestBody.put("options", Map.of("num_predict", config.getMaxTokens()));

        Map response = webClient.post()
                .uri(config.getBaseUrl() + "/api/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofSeconds(config.getTimeout()));

        return AgentResponse.builder()
                .content((String) response.get("response"))
                .llmProvider(config.getName())
                .completed(true)
                .status("COMPLETED")
                .build();
    }

    @Override
    public String streamCall(String prompt, AgentRequest request) {
        // 流式调用返回 SSE 格式
        String provider = selectProvider(request);
        LLMProviderConfig config = providerConfigs.get(provider);

        if (config == null) {
            throw new AgentException(ErrorCode.LLM_PROVIDER_ERROR.getCode(),
                    "LLM provider not configured: " + provider);
        }

        // 返回 SSE 格式的响应
        return buildStreamResponse(prompt, config);
    }

    private String buildStreamResponse(String prompt, LLMProviderConfig config) {
        // 构建流式请求
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", config.getModel());
        requestBody.put("messages", new Object[]{
                Map.of("role", "user", "content", prompt)
        });
        requestBody.put("temperature", config.getTemperature());
        requestBody.put("max_tokens", config.getMaxTokens());
        requestBody.put("stream", true);

        // 对于 Azure 或特殊 Provider，调整请求格式
        String uri = config.getBaseUrl() + "/v1/chat/completions";
        if ("azure".equals(config.getName())) {
            uri = String.format("%s/openai/deployments/%s/chat/completions?api-version=%s",
                    config.getBaseUrl(), config.getModel(), config.getApiVersion());
        } else if (config.isLocal()) {
            uri = config.getBaseUrl() + "/api/chat";
            requestBody.put("stream", true);
        }

        // 发起流式请求
        return webClient.post()
                .uri(uri)
                .header("Authorization", "Bearer " + config.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(config.getTimeout()));
    }

    @Override
    public String selectProvider(AgentRequest request) {
        // 1. 优先使用请求指定的 Provider
        if (request.getLlmProvider() != null &&
                providerConfigs.containsKey(request.getLlmProvider())) {
            return request.getLlmProvider();
        }

        // 2. 使用默认 Provider
        if (providerConfigs.containsKey(defaultProvider)) {
            return defaultProvider;
        }

        // 3. 降级策略: 遍历可用 Provider
        for (String provider : providerConfigs.keySet()) {
            if (providerConfigs.get(provider).getApiKey() != null) {
                log.warn("Default provider {} not available, using fallback: {}",
                        defaultProvider, provider);
                return provider;
            }
        }

        throw new AgentException(ErrorCode.LLM_PROVIDER_ERROR.getCode(),
                "No available LLM provider");
    }

    @Override
    public String summarize(String content) {
        String summarizePrompt = String.format(
                "请将以下对话内容压缩成2-3句话的摘要，保留关键信息：\n\n%s",
                content
        );

        try {
            AgentRequest request = AgentRequest.builder()
                    .userInput(summarizePrompt)
                    .build();

            AgentResponse response = call(summarizePrompt, request);
            return response.getContent();
        } catch (Exception e) {
            log.error("Failed to generate summary", e);
            return "Summary: " + content.substring(0, Math.min(100, content.length()));
        }
    }

    /**
     * LLM Provider 配置
     */
    @lombok.Data
    @lombok.Builder
    private static class LLMProviderConfig {
        private String name;
        private String baseUrl;
        private String model;
        private String apiKey;
        private int timeout;
        private int maxTokens;
        private double temperature;
        private boolean isAzure;
        private String apiVersion;
        private boolean isLocal;
    }
}
