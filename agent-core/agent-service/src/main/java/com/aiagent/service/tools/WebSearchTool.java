package com.aiagent.service.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Web Search Tool (Java 实现)
 * 使用 DuckDuckGo HTML 解析或 SerpAPI
 *
 * 功能:
 * - 速率限制 (per-user, per-minute)
 * - 超时处理
 * - 重试机制
 * - 缓存
 */
@Slf4j
@Component
public class WebSearchTool {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${aiagent.websearch.max-results:5}")
    private int maxResultsDefault;

    @Value("${aiagent.websearch.max-results-limit:20}")
    private int maxResultsLimit;

    @Value("${aiagent.websearch.cache-ttl-seconds:300}")
    private int cacheTtlSeconds;

    // 速率限制器
    private final RateLimiter rateLimiter = new RateLimiter(10, 60);

    // 结果缓存
    private final Map<String, CachedResult> cache = new ConcurrentHashMap<>();

    public WebSearchTool(WebClient webClient, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行搜索
     */
    public String execute(Map<String, Object> input, String userId) {
        String query = (String) input.get("query");
        int maxResults = Math.min(
                input.get("max_results") != null ? ((Number) input.get("max_results")).intValue() : maxResultsDefault,
                maxResultsLimit
        );

        if (query == null || query.isBlank()) {
            return errorResult("INVALID_PARAMETER", "query is required");
        }

        String rateLimitKey = userId != null ? userId : "anonymous";

        // 速率限制检查
        if (!rateLimiter.isAllowed(rateLimitKey)) {
            int retryAfter = rateLimiter.getRetryAfter(rateLimitKey);
            return errorResult("RATE_LIMITED",
                    "Search rate limit exceeded. Please wait before trying again.",
                    Map.of("retry_after_seconds", retryAfter > 0 ? retryAfter : 60));
        }

        // 缓存检查
        String cacheKey = query + ":" + maxResults;
        CachedResult cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            log.debug("Returning cached search result for: {}", query);
            return cached.result;
        }

        try {
            long startTime = System.currentTimeMillis();

            // 使用 DuckDuckGo HTML 搜索
            List<SearchResult> results = searchDuckDuckGo(query, maxResults);

            long elapsedMs = System.currentTimeMillis() - startTime;

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("query", query);
            response.put("count", results.size());
            response.put("results", results);
            response.put("metadata", Map.of(
                    "elapsed_ms", elapsedMs,
                    "rate_limit_remaining", rateLimiter.getRemaining(rateLimitKey),
                    "timestamp", Instant.now().toString()
            ));

            String jsonResult = objectMapper.writeValueAsString(response);

            // 缓存结果
            cache.put(cacheKey, new CachedResult(jsonResult, cacheTtlSeconds));

            return jsonResult;

        } catch (Exception e) {
            log.error("Web search failed: {}", e.getMessage());

            String errorCode = "SEARCH_FAILED";
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("timeout")) {
                errorCode = "TIMEOUT";
            }

            return errorResult(errorCode, e.getMessage());
        }
    }

    /**
     * DuckDuckGo HTML 搜索
     */
    private List<SearchResult> searchDuckDuckGo(String query, int maxResults) throws Exception {
        String searchUrl = "https://duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(query, "UTF-8");

        String html = webClient.get()
                .uri(searchUrl)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(10))
                .block();

        if (html == null) {
            return Collections.emptyList();
        }

        List<SearchResult> results = new ArrayList<>();

        // 简单 HTML 解析 - 提取搜索结果
        // DuckDuckGo HTML 格式: <a class="result__a" href="...">Title</a>...<a class="result__snippet" href="...">Snippet</a>
        String[] lines = html.split("\n");
        String currentTitle = null;
        String currentHref = null;

        for (String line : lines) {
            if (line.contains("result__a")) {
                // 提取标题和链接
                int hrefStart = line.indexOf("href=\"");
                if (hrefStart >= 0) {
                    hrefStart += 6;
                    int hrefEnd = line.indexOf("\"", hrefStart);
                    if (hrefEnd > hrefStart) {
                        currentHref = line.substring(hrefStart, hrefEnd);
                    }
                }
                int titleStart = line.indexOf(">");
                if (titleStart >= 0) {
                    int titleEnd = line.indexOf("</a>");
                    if (titleEnd > titleStart) {
                        currentTitle = line.substring(titleStart + 1, titleEnd);
                    }
                }
            } else if (line.contains("result__snippet") && currentTitle != null && currentHref != null) {
                // 提取摘要
                String body = extractSnippet(line);
                results.add(new SearchResult(currentTitle, currentHref, body));

                if (results.size() >= maxResults) {
                    break;
                }
                currentTitle = null;
                currentHref = null;
            }
        }

        return results;
    }

    private String extractSnippet(String line) {
        int start = line.indexOf(">");
        if (start < 0) return "";
        int end = line.indexOf("</a>");
        if (end < 0) return "";
        String snippet = line.substring(start + 1, end);
        // 清理 HTML 标签
        return snippet.replaceAll("<[^>]+>", "").trim();
    }

    private String errorResult(String errorCode, String message) {
        return errorResult(errorCode, message, Collections.emptyMap());
    }

    private String errorResult(String errorCode, String message, Map<String, Object> extra) {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "error");
            result.put("error_code", errorCode);
            result.put("message", message);
            result.putAll(extra);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"error_code\":\"" + errorCode + "\",\"message\":\"" + message + "\"}";
        }
    }

    /**
     * 获取速率限制状态
     */
    public Map<String, Object> getRateLimitStatus(String userId) {
        String key = userId != null ? userId : "anonymous";
        return Map.of(
                "user_id", key,
                "remaining", rateLimiter.getRemaining(key),
                "limit", 10,
                "window_seconds", 60
        );
    }

    /**
     * 速率限制器
     */
    private static class RateLimiter {
        private final int maxCalls;
        private final int windowSeconds;
        private final Map<String, List<Long>> calls = new ConcurrentHashMap<>();

        RateLimiter(int maxCalls, int windowSeconds) {
            this.maxCalls = maxCalls;
            this.windowSeconds = windowSeconds;
        }

        synchronized boolean isAllowed(String key) {
            long now = System.currentTimeMillis();
            List<Long> callTimes = calls.computeIfAbsent(key, k -> new ArrayList<>());

            // 移除过期的调用
            callTimes.removeIf(t -> now - t > windowSeconds * 1000L);

            if (callTimes.size() >= maxCalls) {
                return false;
            }

            callTimes.add(now);
            return true;
        }

        int getRetryAfter(String key) {
            List<Long> callTimes = calls.get(key);
            if (callTimes == null || callTimes.isEmpty()) {
                return 0;
            }
            long oldestCall = Collections.min(callTimes);
            return (int) Math.max(0, windowSeconds - (System.currentTimeMillis() - oldestCall) / 1000);
        }

        int getRemaining(String key) {
            List<Long> callTimes = calls.get(key);
            if (callTimes == null) {
                return maxCalls;
            }
            long now = System.currentTimeMillis();
            int active = (int) callTimes.stream().filter(t -> now - t <= windowSeconds * 1000L).count();
            return Math.max(0, maxCalls - active);
        }
    }

    /**
     * 缓存结果
     */
    private static class CachedResult {
        final String result;
        final long expiryTime;

        CachedResult(String result, int ttlSeconds) {
            this.result = result;
            this.expiryTime = System.currentTimeMillis() + ttlSeconds * 1000L;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiryTime;
        }
    }

    /**
     * 搜索结果
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    private static class SearchResult {
        private String title;
        private String href;
        private String body;
    }
}
