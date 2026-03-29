package com.aiagent.service.browser;

import com.aiagent.service.plugin.Plugin;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * 浏览器自动化插件
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class BrowserPlugin implements Plugin {

    private final BrowserManager browserManager;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private PluginContext context;
    private String currentSessionId;

    @Override
    public String getId() {
        return "builtin.browser";
    }

    @Override
    public String getName() {
        return "Browser Automation";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "Automated browser operations using Selenium WebDriver";
    }

    @Override
    public PluginType getType() {
        return PluginType.TOOL;
    }

    @Override
    public void initialize(PluginContext context) throws PluginException {
        this.context = context;
        this.currentSessionId = "browser-" + System.currentTimeMillis();
        log.info("BrowserPlugin initialized with session: {}", currentSessionId);
    }

    @Override
    public String execute(String input) throws PluginException {
        if (context == null) {
            throw new PluginException("Plugin not initialized");
        }

        try {
            BrowserRequest request = objectMapper.readValue(input, BrowserRequest.class);
            Browser browser = browserManager.getSession(request.getSessionId() != null
                    ? request.getSessionId() : currentSessionId);

            BrowserResponse response = executeAction(browser, request);
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new PluginException("Failed to parse request: " + e.getMessage(), e);
        } catch (Browser.BrowserException e) {
            throw new PluginException("Browser operation failed: " + e.getMessage(), e);
        }
    }

    private BrowserResponse executeAction(Browser browser, BrowserRequest request) throws Browser.BrowserException {
        BrowserResponse response = new BrowserResponse();
        response.setSessionId(request.getSessionId() != null ? request.getSessionId() : currentSessionId);

        switch (request.getAction()) {
            case "open":
                browser.open(request.getUrl());
                response.setSuccess(true);
                response.setMessage("Page opened: " + request.getUrl());
                break;

            case "click":
                browser.click(request.getSelector());
                response.setSuccess(true);
                response.setMessage("Element clicked: " + request.getSelector());
                break;

            case "input":
                browser.input(request.getSelector(), request.getText());
                response.setSuccess(true);
                response.setMessage("Text input to: " + request.getSelector());
                break;

            case "getContent":
                response.setSuccess(true);
                response.setContent(browser.getContent());
                break;

            case "getTitle":
                response.setSuccess(true);
                response.setTitle(browser.getTitle());
                break;

            case "screenshot":
                byte[] screenshot = browser.screenshot();
                response.setSuccess(true);
                response.setScreenshot(Base64.getEncoder().encodeToString(screenshot));
                break;

            case "wait":
                browser.waitFor(request.getSelector(), request.getTimeout() != null ? request.getTimeout() : 10);
                response.setSuccess(true);
                response.setMessage("Wait completed for: " + request.getSelector());
                break;

            case "executeScript":
                String result = browser.executeScript(request.getScript());
                response.setSuccess(true);
                response.setContent(result);
                break;

            default:
                response.setSuccess(false);
                response.setMessage("Unknown action: " + request.getAction());
        }

        return response;
    }

    @Override
    public void destroy() {
        if (currentSessionId != null) {
            browserManager.closeSession(currentSessionId);
        }
        log.info("BrowserPlugin destroyed");
    }

    /**
     * 浏览器请求
     */
    @lombok.Data
    public static class BrowserRequest {
        private String sessionId;
        private String action; // open, click, input, getContent, getTitle, screenshot, wait, executeScript
        private String url;
        private String selector;
        private String text;
        private String script;
        private Integer timeout;
    }

    /**
     * 浏览器响应
     */
    @lombok.Data
    public static class BrowserResponse {
        private String sessionId;
        private boolean success;
        private String message;
        private String content;
        private String title;
        private String screenshot;
    }
}
