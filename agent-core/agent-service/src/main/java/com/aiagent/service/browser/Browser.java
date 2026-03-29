package com.aiagent.service.browser;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 浏览器自动化接口 - 参考 OpenClaw Browser
 */
public interface Browser {

    /**
     * 打开 URL
     */
    void open(String url) throws BrowserException;

    /**
     * 点击元素
     */
    void click(String selector) throws BrowserException;

    /**
     * 输入文本
     */
    void input(String selector, String text) throws BrowserException;

    /**
     * 获取页面内容
     */
    String getContent() throws BrowserException;

    /**
     * 获取页面标题
     */
    String getTitle() throws BrowserException;

    /**
     * 执行 JavaScript
     */
    String executeScript(String script) throws BrowserException;

    /**
     * 截图
     */
    byte[] screenshot() throws BrowserException;

    /**
     * 等待元素出现
     */
    void waitFor(String selector, int timeoutSeconds) throws BrowserException;

    /**
     * 关闭浏览器
     */
    void close();

    /**
     * 浏览器异常
     */
    class BrowserException extends Exception {
        public BrowserException(String message) {
            super(message);
        }

        public BrowserException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 页面元素
     */
    @Data
    class Element {
        private String tagName;
        private String text;
        private String href;
        private boolean enabled;
        private boolean visible;
        private Map<String, String> attributes;
    }

    /**
     * 浏览器配置
     */
    @Data
    class BrowserConfig {
        private String browserType = "chrome"; // chrome, firefox, edge
        private boolean headless = true;
        private int timeout = 30000;
        private int windowWidth = 1920;
        private int windowHeight = 1080;
        private List<String> arguments; // 浏览器启动参数
    }
}
