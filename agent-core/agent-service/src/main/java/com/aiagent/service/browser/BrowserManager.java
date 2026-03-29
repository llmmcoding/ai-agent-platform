package com.aiagent.service.browser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 浏览器会话管理器
 * 管理多个浏览器会话
 */
@Slf4j
@Component
public class BrowserManager {

    /**
     * 浏览器会话缓存
     */
    private final Map<String, Browser> sessions = new ConcurrentHashMap<>();

    /**
     * 默认浏览器配置
     */
    private final Browser.BrowserConfig defaultConfig = new Browser.BrowserConfig();

    /**
     * 创建或获取浏览器会话
     */
    public Browser getSession(String sessionId) {
        return sessions.computeIfAbsent(sessionId, k -> createBrowser());
    }

    /**
     * 创建浏览器实例
     */
    public Browser createBrowser() {
        return new SeleniumBrowser(defaultConfig);
    }

    /**
     * 创建带配置的浏览器
     */
    public Browser createBrowser(Browser.BrowserConfig config) {
        return new SeleniumBrowser(config);
    }

    /**
     * 关闭指定会话
     */
    public void closeSession(String sessionId) {
        Browser browser = sessions.remove(sessionId);
        if (browser != null) {
            try {
                browser.close();
                log.info("Browser session closed: {}", sessionId);
            } catch (Exception e) {
                log.error("Error closing browser session: {}", sessionId, e);
            }
        }
    }

    /**
     * 关闭所有会话
     */
    public void closeAll() {
        sessions.forEach((id, browser) -> {
            try {
                browser.close();
            } catch (Exception e) {
                log.error("Error closing browser: {}", id, e);
            }
        });
        sessions.clear();
        log.info("All browser sessions closed");
    }

    /**
     * 获取活跃会话数
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }

    /**
     * 检查会话是否存在
     */
    public boolean hasSession(String sessionId) {
        return sessions.containsKey(sessionId);
    }
}
