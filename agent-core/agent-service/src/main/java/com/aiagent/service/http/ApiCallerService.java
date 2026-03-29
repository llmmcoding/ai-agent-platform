package com.aiagent.service.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.*;

/**
 * High-performance HTTP API Caller Service
 * Java implementation for better performance than Python
 */
@Slf4j
@Service
public class ApiCallerService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${aiagent.api-caller.default-timeout:30}")
    private int defaultTimeout;

    @Value("${aiagent.api-caller.max-timeout:120}")
    private int maxTimeout;

    @Value("${aiagent.api-caller.environment:development}")
    private String environment;

    // Allowed hosts for SSRF protection in production
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "api.openweathermap.org",
            "weatherapi.com",
            "api.github.com",
            "api.twitter.com",
            "api.openai.com",
            "api.anthropic.com",
            "api.minimaxi.com",
            "api.minimax.chat"
    );

    @Autowired
    public ApiCallerService(WebClient webClient, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Execute HTTP API call
     *
     * @param input Map containing:
     *              - url: String (required) - Full URL to call
     *              - method: String (optional) - GET, POST, PUT, DELETE, PATCH, default GET
     *              - headers: Map (optional) - Request headers
     *              - body: Object (optional) - Request body (JSON)
     *              - params: Map (optional) - Query parameters
     *              - timeout: Integer (optional) - Timeout in seconds, default 30, max 120
     *              - oauth_token: String (optional) - OAuth Bearer token
     *              - api_key: String (optional) - API key for header injection
     * @return JSON string with response data
     */
    public String execute(Map<String, Object> input) {
        String url = (String) input.get("url");
        String method = ((String) input.getOrDefault("method", "GET")).toUpperCase();
        Map<String, Object> headers = (Map<String, Object>) input.get("headers");
        Object body = input.get("body");
        Map<String, Object> params = (Map<String, Object>) input.get("params");
        Integer timeout = input.get("timeout") != null
                ? Math.min(((Number) input.get("timeout")).intValue(), maxTimeout)
                : defaultTimeout;

        // OAuth/API key injection
        String oauthToken = (String) input.getOrDefault("oauth_token",
                System.getenv("DEFAULT_API_TOKEN"));
        String apiKey = (String) input.getOrDefault("api_key",
                System.getenv("DEFAULT_API_KEY"));

        // Validate URL
        if (url == null || url.isBlank()) {
            return buildErrorResponse("INVALID_PARAMETER", "url is required", null);
        }

        // Validate URL scheme
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
                return buildErrorResponse("INVALID_URL", "Only HTTP and HTTPS URLs are allowed", null);
            }

            // SSRF protection: validate host in production
            if ("production".equals(environment)) {
                String host = uri.getHost();
                if (host != null && !ALLOWED_HOSTS.contains(host)) {
                    return buildErrorResponse("HOST_NOT_ALLOWED",
                            "Host '" + host + "' is not in the allowed list for security reasons", null);
                }
            }

            // Validate method
            Set<String> allowedMethods = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");
            if (!allowedMethods.contains(method)) {
                return buildErrorResponse("INVALID_METHOD",
                        "Method '" + method + "' not allowed. Use: " + String.join(", ", allowedMethods), null);
            }

            // Build headers
            HttpHeaders httpHeaders = new HttpHeaders();
            if (headers != null) {
                headers.forEach((key, value) -> {
                    if (value != null) {
                        httpHeaders.add(key, String.valueOf(value));
                    }
                });
            }

            // OAuth/API key injection
            if (oauthToken != null && !oauthToken.isBlank() && !httpHeaders.containsKey("Authorization")) {
                httpHeaders.add("Authorization", "Bearer " + oauthToken);
            } else if (apiKey != null && !apiKey.isBlank() && !httpHeaders.containsKey("Authorization")
                    && !httpHeaders.containsKey("X-API-Key")) {
                httpHeaders.add("X-API-Key", apiKey);
            }

            // Set default content type if not set
            if (!httpHeaders.containsKey(HttpHeaders.CONTENT_TYPE)) {
                httpHeaders.setContentType(MediaType.APPLICATION_JSON);
            }

            // Execute request
            long startTime = System.currentTimeMillis();

            Mono<String> responseMono = executeRequest(url, method, httpHeaders, body, params, timeout);

            // Block and wait for response (synchronous execution for ReAct)
            String response = responseMono.block(Duration.ofSeconds(timeout));

            long elapsedMs = System.currentTimeMillis() - startTime;

            return parseResponse(response, elapsedMs, httpHeaders);

        } catch (IllegalArgumentException e) {
            return buildErrorResponse("INVALID_URL", "Invalid URL format: " + e.getMessage(), null);
        } catch (Exception e) {
            log.error("API request failed", e);
            return buildErrorResponse("INTERNAL_ERROR", "Unexpected error: " + e.getMessage(), null);
        }
    }

    private Mono<String> executeRequest(String url, String method, HttpHeaders headers,
                                          Object body, Map<String, Object> params, int timeout) {
        WebClient.RequestBodySpec requestSpec = webClient.method(HttpMethod.valueOf(method))
                .uri(uriBuilder -> {
                    UriBuilder builder = uriBuilder.path(url);
                    if (params != null && !params.isEmpty()) {
                        params.forEach((key, value) -> {
                            if (value != null) {
                                builder.queryParam(key, value);
                            }
                        });
                    }
                    return builder.build();
                })
                .headers(h -> h.addAll(headers));

        Mono<String> resultMono;
        if (body != null && ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method))) {
            resultMono = requestSpec.bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class);
        } else {
            resultMono = requestSpec
                    .retrieve()
                    .bodyToMono(String.class);
        }
        return resultMono.timeout(Duration.ofSeconds(timeout));
    }

    private String parseResponse(String responseBody, long elapsedMs, HttpHeaders requestHeaders) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("elapsed_ms", elapsedMs);

        // Try to parse JSON response
        try {
            Object parsed = objectMapper.readValue(responseBody, Object.class);
            result.put("body", parsed);
        } catch (Exception e) {
            // Not JSON, treat as text
            String truncatedText = responseBody.length() > 10000
                    ? responseBody.substring(0, 10000) + "...[truncated]"
                    : responseBody;
            result.put("body", truncatedText);
            result.put("body_type", "text");
        }

        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            result.put("body", responseBody);
            try {
                return objectMapper.writeValueAsString(result);
            } catch (Exception e2) {
                return "{\"status\":\"success\",\"body\":\"" + responseBody + "\"}";
            }
        }
    }

    private String buildErrorResponse(String errorCode, String message, Integer statusCode) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("status", "error");
        error.put("error_code", errorCode);
        error.put("message", message);
        if (statusCode != null) {
            error.put("status_code", statusCode);
        }

        try {
            return objectMapper.writeValueAsString(error);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"error_code\":\"" + errorCode + "\",\"message\":\"" + message + "\"}";
        }
    }

    /**
     * Check if a hostname is allowed for API calls
     */
    public boolean isHostAllowed(String hostname) {
        if (!"production".equals(environment)) {
            return true;
        }
        return ALLOWED_HOSTS.contains(hostname);
    }

    /**
     * Add a hostname to the allowed list (for runtime configuration)
     */
    public void addAllowedHost(String hostname) {
        // This would require a mutable set in production
        log.info("Adding allowed host: {}", hostname);
    }
}
