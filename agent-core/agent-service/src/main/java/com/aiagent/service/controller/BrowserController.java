package com.aiagent.service.controller;

import com.aiagent.common.Result;
import com.aiagent.service.browser.Browser;
import com.aiagent.service.browser.BrowserManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.Map;

/**
 * 浏览器自动化 API
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/browser")
@RequiredArgsConstructor
public class BrowserController {

    private final BrowserManager browserManager;

    /**
     * 创建浏览器会话
     */
    @PostMapping("/session")
    public Result<Map<String, String>> createSession() {
        String sessionId = "browser-" + System.currentTimeMillis();
        browserManager.getSession(sessionId);
        return Result.success(Map.of(
                "sessionId", sessionId,
                "message", "Browser session created"
        ));
    }

    /**
     * 关闭浏览器会话
     */
    @DeleteMapping("/session/{sessionId}")
    public Result<Void> closeSession(@PathVariable String sessionId) {
        browserManager.closeSession(sessionId);
        return Result.success(null);
    }

    /**
     * 执行浏览器操作
     */
    @PostMapping("/session/{sessionId}/action")
    public Result<Object> executeAction(
            @PathVariable String sessionId,
            @RequestBody BrowserActionRequest request) {
        Browser browser = browserManager.getSession(sessionId);
        if (browser == null) {
            return Result.error("Session not found: " + sessionId);
        }

        try {
            switch (request.getAction()) {
                case "open":
                    browser.open(request.getUrl());
                    return Result.success(Map.of("success", true, "message", "Page opened"));

                case "click":
                    browser.click(request.getSelector());
                    return Result.success(Map.of("success", true, "message", "Element clicked"));

                case "input":
                    browser.input(request.getSelector(), request.getText());
                    return Result.success(Map.of("success", true, "message", "Text input"));

                case "getContent":
                    return Result.success(Map.of("success", true, "content", browser.getContent()));

                case "getTitle":
                    return Result.success(Map.of("success", true, "title", browser.getTitle()));

                case "screenshot":
                    byte[] screenshot = browser.screenshot();
                    String base64 = Base64.getEncoder().encodeToString(screenshot);
                    return Result.success(Map.of("success", true, "screenshot", base64));

                case "wait":
                    browser.waitFor(request.getSelector(), request.getTimeout() != null ? request.getTimeout() : 10);
                    return Result.success(Map.of("success", true, "message", "Wait completed"));

                case "executeScript":
                    String result = browser.executeScript(request.getScript());
                    return Result.success(Map.of("success", true, "result", result));

                default:
                    return Result.error("Unknown action: " + request.getAction());
            }
        } catch (Browser.BrowserException e) {
            log.error("Browser action failed: {}", request.getAction(), e);
            return Result.error("Browser error: " + e.getMessage());
        }
    }

    /**
     * 获取活跃会话数
     */
    @GetMapping("/sessions/count")
    public Result<Map<String, Object>> getSessionCount() {
        return Result.success(Map.of(
                "activeSessions", browserManager.getActiveSessionCount()
        ));
    }

    /**
     * 浏览器操作请求
     */
    @lombok.Data
    public static class BrowserActionRequest {
        private String action;
        private String url;
        private String selector;
        private String text;
        private String script;
        private Integer timeout;
    }
}
