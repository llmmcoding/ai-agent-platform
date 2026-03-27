package com.aiagent.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.retry.annotation.EnableRetry;

/**
 * AI Agent Platform - Spring Boot Application
 *
 * 企业级 AI Agent 能力平台主启动类
 */
@SpringBootApplication
@EnableRetry
@ComponentScan(basePackages = {"com.aiagent"})
public class AgentBootApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentBootApplication.class, args);
    }
}
