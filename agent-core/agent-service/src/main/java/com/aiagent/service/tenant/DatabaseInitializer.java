package com.aiagent.service.tenant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

/**
 * 数据库初始化器 - 在应用启动时执行数据库迁移
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseInitializer {

    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void initializeDatabase() {
        log.info("Checking database schema...");
        try {
            // 检查表是否存在
            Integer tableCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'ai_tenant'",
                    Integer.class);

            if (tableCount != null && tableCount > 0) {
                log.info("Database schema already exists, checking platform key...");

                // 检查并修复平台管理员 Key
                Integer keyCount = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_platform_api_key WHERE key_alias = 'sk-platform-xxxx1234'",
                        Integer.class);

                if (keyCount != null && keyCount > 0) {
                    // 更新为正确的 SHA-256 哈希
                    // SHA-256("sk-platform-xxxx1234") = d697001b2da641a2c100a47fa348c072b0d50c7f6c07633e6e5d6a26132e3ff0
                    jdbcTemplate.update(
                            "UPDATE ai_platform_api_key SET key_hash = ? WHERE key_alias = 'sk-platform-xxxx1234'",
                            "d697001b2da641a2c100a47fa348c072b0d50c7f6c07633e6e5d6a26132e3ff0"
                    );
                    log.info("Platform admin key hash updated");
                }

                // 检查是否需要运行 V2 迁移
                Integer usageTableCount = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'api_usage_record'",
                        Integer.class);

                if (usageTableCount == null || usageTableCount == 0) {
                    log.info("Running V2 migration for usage tables...");
                    runMigration("V2__add_usage_tables.sql");
                }

                // 检查是否需要运行 V3 迁移
                Integer auditTableCount = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'api_audit_log'",
                        Integer.class);

                if (auditTableCount == null || auditTableCount == 0) {
                    log.info("Running V3 migration for audit tables...");
                    runMigration("V3__add_audit_tables.sql");
                }

                return;
            }

            log.info("Database schema not found, running migration...");

            // 使用 ResourceDatabasePopulator 执行 SQL 脚本
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new ClassPathResource("db/migration/V1__create_tenant_tables.sql"));
            populator.setContinueOnError(true);
            populator.execute(jdbcTemplate.getDataSource());

            // 修复平台管理员 Key 哈希
            jdbcTemplate.update(
                    "UPDATE ai_platform_api_key SET key_hash = ? WHERE key_alias = 'sk-platform-xxxx1234'",
                    "d697001b2da641a2c100a47fa348c072b0d50c7f6c07633e6e5d6a26132e3ff0"
            );

            // 运行 V2 迁移
            runMigration("V2__add_usage_tables.sql");

            // 运行 V3 迁移
            runMigration("V3__add_audit_tables.sql");

            log.info("Database migration completed successfully");

        } catch (Exception e) {
            log.error("Database initialization failed: {}", e.getMessage(), e);
        }
    }

    private void runMigration(String filename) {
        try {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new ClassPathResource("db/migration/" + filename));
            populator.setContinueOnError(true);
            populator.execute(jdbcTemplate.getDataSource());
            log.info("Migration {} completed", filename);
        } catch (Exception e) {
            log.error("Migration {} failed: {}", filename, e.getMessage());
        }
    }
}
