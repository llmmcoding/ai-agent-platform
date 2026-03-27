package com.aiagent.service.pgvector;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * pgvector 配置
 * 使用 HikariCP 连接池管理 PostgreSQL 连接
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "aiagent.pgvector")
public class PGVectorConfig {

    private String host = "localhost";
    private int port = 5432;
    private String user = "postgres";
    private String password = "postgres";
    private String database = "postgres";
    private int poolSize = 20;

    @Bean
    public DataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(String.format("jdbc:postgresql://%s:%d/%s", host, port, database));
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(5);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        log.info("PGVector DataSource configured: {}:{}/{}, poolSize: {}",
                host, port, database, poolSize);

        return new HikariDataSource(config);
    }
}
