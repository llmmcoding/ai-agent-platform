package com.aiagent.service.browser;

import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Selenium 浏览器实现
 */
@Slf4j
public class SeleniumBrowser implements Browser {

    private WebDriver driver;
    private BrowserConfig config;
    private WebDriverWait wait;

    public SeleniumBrowser() {
        this.config = new BrowserConfig();
    }

    public SeleniumBrowser(BrowserConfig config) {
        this.config = config != null ? config : new BrowserConfig();
    }

    private void ensureDriver() {
        if (driver == null) {
            initDriver();
        }
    }

    private void initDriver() {
        ChromeOptions chromeOptions = new ChromeOptions();
        if (config.isHeadless()) {
            chromeOptions.addArguments("--headless");
        }
        chromeOptions.addArguments(
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-gpu",
                "--window-size=" + config.getWindowWidth() + "," + config.getWindowHeight()
        );

        if (config.getArguments() != null) {
            config.getArguments().forEach(chromeOptions::addArguments);
        }

        // 禁用图片加载以提高性能
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("profile.managed_default_content_settings.images", 2);
        chromeOptions.setExperimentalOption("prefs", prefs);

        driver = new ChromeDriver(chromeOptions);
        driver.manage().timeouts().pageLoadTimeout(Duration.ofMillis(config.getTimeout()));
        driver.manage().timeouts().scriptTimeout(Duration.ofMillis(config.getTimeout()));
        wait = new WebDriverWait(driver, Duration.ofSeconds(config.getTimeout() / 1000));
    }

    @Override
    public void open(String url) throws BrowserException {
        ensureDriver();
        try {
            driver.get(url);
            log.info("Opened URL: {}", url);
        } catch (Exception e) {
            throw new BrowserException("Failed to open URL: " + url, e);
        }
    }

    @Override
    public void click(String selector) throws BrowserException {
        ensureDriver();
        try {
            WebElement element = findElement(selector);
            element.click();
            log.debug("Clicked element: {}", selector);
        } catch (Exception e) {
            throw new BrowserException("Failed to click element: " + selector, e);
        }
    }

    @Override
    public void input(String selector, String text) throws BrowserException {
        ensureDriver();
        try {
            WebElement element = findElement(selector);
            element.clear();
            element.sendKeys(text);
            log.debug("Input text to element: {}", selector);
        } catch (Exception e) {
            throw new BrowserException("Failed to input text to element: " + selector, e);
        }
    }

    @Override
    public String getContent() throws BrowserException {
        ensureDriver();
        try {
            return driver.getPageSource();
        } catch (Exception e) {
            throw new BrowserException("Failed to get page content", e);
        }
    }

    @Override
    public String getTitle() throws BrowserException {
        ensureDriver();
        try {
            return driver.getTitle();
        } catch (Exception e) {
            throw new BrowserException("Failed to get page title", e);
        }
    }

    @Override
    public String executeScript(String script) throws BrowserException {
        ensureDriver();
        try {
            Object result = ((JavascriptExecutor) driver).executeScript(script);
            return result != null ? result.toString() : "";
        } catch (Exception e) {
            throw new BrowserException("Failed to execute script", e);
        }
    }

    @Override
    public byte[] screenshot() throws BrowserException {
        ensureDriver();
        try {
            TakesScreenshot ts = (TakesScreenshot) driver;
            return ts.getScreenshotAs(OutputType.BYTES);
        } catch (Exception e) {
            throw new BrowserException("Failed to take screenshot", e);
        }
    }

    @Override
    public void waitFor(String selector, int timeoutSeconds) throws BrowserException {
        ensureDriver();
        try {
            By by = toBy(selector);
            wait.withTimeout(Duration.ofSeconds(timeoutSeconds));
            wait.until(d -> d.findElements(by).size() > 0);
            log.debug("Waited for element: {} (timeout: {}s)", selector, timeoutSeconds);
        } catch (Exception e) {
            throw new BrowserException("Failed to wait for element: " + selector, e);
        }
    }

    @Override
    public void close() {
        if (driver != null) {
            try {
                driver.quit();
                log.info("Browser closed");
            } catch (Exception e) {
                log.error("Error closing browser", e);
            } finally {
                driver = null;
            }
        }
    }

    private WebElement findElement(String selector) {
        By by = toBy(selector);
        return wait.until(d -> {
            List<WebElement> elements = d.findElements(by);
            return elements.stream()
                    .filter(WebElement::isDisplayed)
                    .findFirst()
                    .orElse(null);
        });
    }

    private By toBy(String selector) {
        if (selector.startsWith("//") || selector.startsWith("(//")) {
            return By.xpath(selector);
        } else if (selector.startsWith("#")) {
            return By.id(selector.substring(1));
        } else if (selector.startsWith(".")) {
            return By.className(selector.substring(1));
        } else if (selector.startsWith("[")) {
            return By.cssSelector(selector);
        } else if (selector.startsWith("<")) {
            String tag = selector.substring(1, selector.length() - 1);
            return By.tagName(tag);
        } else {
            // 默认按 CSS 选择器
            return By.cssSelector(selector);
        }
    }
}
