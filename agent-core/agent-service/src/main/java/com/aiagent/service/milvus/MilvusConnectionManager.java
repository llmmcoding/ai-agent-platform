package com.aiagent.service.milvus;

import io.milvus.client.MilvusClient;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Milvus 连接客户端 (SDK 2.x)
 * 负责管理 Milvus 连接
 */
@Slf4j
@Component
public class MilvusConnectionManager {

    @Value("${aiagent.milvus.host:localhost}")
    private String host;

    @Value("${aiagent.milvus.port:19530}")
    private int port;

    @Value("${aiagent.milvus.connectionPoolSize:20}")
    private int poolSize;

    private MilvusClient milvusClient;

    @PostConstruct
    public void init() {
        try {
            ConnectParam connectParam = ConnectParam.newBuilder()
                    .withHost(host)
                    .withPort(port)
                    .build();

            this.milvusClient = new MilvusServiceClient(connectParam);
            log.info("Milvus client initialized with pool size: {}, host: {}, port: {}", poolSize, host, port);
        } catch (Exception e) {
            log.error("Failed to initialize Milvus client: {}", e.getMessage(), e);
        }
    }

    /**
     * 获取 Milvus 客户端
     */
    public MilvusClient getClient() {
        return milvusClient;
    }

    /**
     * 检查是否已连接
     */
    public boolean isConnected() {
        return milvusClient != null;
    }

    @PreDestroy
    public void close() {
        if (milvusClient != null) {
            try {
                milvusClient.close();
                log.info("Milvus client closed");
            } catch (Exception e) {
                log.error("Error closing Milvus client: {}", e.getMessage());
            }
        }
    }
}
