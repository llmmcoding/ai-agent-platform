package com.aiagent.service.milvus;

import io.milvus.client.MilvusClient;
import io.milvus.param.ConnectParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Milvus 连接池客户端
 * 支持多 Collection 的连接复用
 */
@Slf4j
@Component
public class MilvusClient {

    @Value("${aiagent.milvus.host:localhost}")
    private String host;

    @Value("${aiagent.milvus.port:19530}")
    private int port;

    @Value("${aiagent.milvus.connection-pool-size:20}")
    private int poolSize;

    private io.milvus.client.MilvusClient client;

    // Collection 到 Collection对象的缓存
    private final Map<String, io.milvus.grpc.SearchResults> collectionCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        ConnectParam connectParam = ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port)
                .withPoolSize(poolSize)
                .build();

        this.client = new io.milvus.client.MilvusClient(connectParam);
        log.info("Milvus client initialized with pool size: {}, host: {}, port: {}", poolSize, host, port);
    }

    /**
     * 获取 Milvus 客户端
     */
    public io.milvus.client.MilvusClient getClient() {
        return client;
    }

    /**
     * 检查是否已连接
     */
    public boolean isConnected() {
        return client != null;
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            client.close();
            log.info("Milvus client closed");
        }
    }
}
