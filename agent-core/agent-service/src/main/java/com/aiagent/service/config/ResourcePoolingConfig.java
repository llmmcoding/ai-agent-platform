package com.aiagent.service.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 资源池化配置
 * 优化连接池、线程池大小以提升 QPS
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "aiagent.pooling")
public class ResourcePoolingConfig {

    /**
     * 工具执行线程池配置
     */
    private int toolCorePoolSize = 50;
    private int toolMaxPoolSize = 100;
    private int toolQueueCapacity = 500;
    private int toolKeepAliveSeconds = 60;

    /**
     * 向量检索线程池配置
     */
    private int vectorCorePoolSize = 100;
    private int vectorMaxPoolSize = 200;
    private int vectorQueueCapacity = 1000;
    private int vectorKeepAliveSeconds = 30;

    /**
     * RAG 批量查询线程池
     */
    private int ragCorePoolSize = 50;
    private int ragMaxPoolSize = 100;
    private int ragQueueCapacity = 500;
    private int ragKeepAliveSeconds = 60;

    /**
     * Milvus 连接池配置
     */
    private int milvusConnectionPoolSize = 100;

    /**
     * PGVector 连接池配置
     */
    private int pgvectorPoolSize = 100;

    /**
     * RestTemplate 连接池配置
     */
    private int restTemplateMaxConnections = 200;
    private int restTemplatePerRoute = 50;
    private int restTemplateConnectTimeout = 5000;
    private int restTemplateSocketTimeout = 30000;

    @Bean(name = "toolExecutor")
    public Executor toolExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(toolCorePoolSize);
        executor.setMaxPoolSize(toolMaxPoolSize);
        executor.setQueueCapacity(toolQueueCapacity);
        executor.setKeepAliveSeconds(toolKeepAliveSeconds);
        executor.setThreadNamePrefix("tool-exec-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        log.info("Tool executor initialized: core={}, max={}, queue={}",
                toolCorePoolSize, toolMaxPoolSize, toolQueueCapacity);
        return executor;
    }

    @Bean(name = "vectorSearchExecutor")
    public Executor vectorSearchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(vectorCorePoolSize);
        executor.setMaxPoolSize(vectorMaxPoolSize);
        executor.setQueueCapacity(vectorQueueCapacity);
        executor.setKeepAliveSeconds(vectorKeepAliveSeconds);
        executor.setThreadNamePrefix("vector-search-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("Vector search executor initialized: core={}, max={}, queue={}",
                vectorCorePoolSize, vectorMaxPoolSize, vectorQueueCapacity);
        return executor;
    }

    @Bean(name = "ragQueryExecutor")
    public Executor ragQueryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(ragCorePoolSize);
        executor.setMaxPoolSize(ragMaxPoolSize);
        executor.setQueueCapacity(ragQueueCapacity);
        executor.setKeepAliveSeconds(ragKeepAliveSeconds);
        executor.setThreadNamePrefix("rag-query-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        log.info("RAG query executor initialized: core={}, max={}, queue={}",
                ragCorePoolSize, ragMaxPoolSize, ragQueueCapacity);
        return executor;
    }

    /**
     * 获取池化统计信息
     */
    public java.util.Map<String, Object> getStats() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("toolExecutor", java.util.Map.of(
                "corePoolSize", toolCorePoolSize,
                "maxPoolSize", toolMaxPoolSize,
                "queueCapacity", toolQueueCapacity
        ));
        stats.put("vectorSearchExecutor", java.util.Map.of(
                "corePoolSize", vectorCorePoolSize,
                "maxPoolSize", vectorMaxPoolSize,
                "queueCapacity", vectorQueueCapacity
        ));
        stats.put("milvusConnectionPoolSize", milvusConnectionPoolSize);
        stats.put("pgvectorPoolSize", pgvectorPoolSize);
        stats.put("restTemplateMaxConnections", restTemplateMaxConnections);
        return stats;
    }
}
