package com.aiagent.service.pgvector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

/**
 * pgvector 向量服务
 * 通过 JDBC 操作 PostgreSQL pgvector 扩展
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PGVectorService {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    @Value("${aiagent.pgvector.embedding-dim:1536}")
    private int embeddingDim;

    @Value("${aiagent.pgvector.collection.default:default}")
    private String defaultCollection;

    @Value("${aiagent.pgvector.search.top-k:20}")
    private int defaultTopK;

    private JdbcTemplate jdbcTemplate;

    private JdbcTemplate getJdbcTemplate() {
        if (jdbcTemplate == null) {
            jdbcTemplate = new JdbcTemplate(dataSource);
        }
        return jdbcTemplate;
    }

    /**
     * 搜索向量 - 使用余弦距离 (<=>)
     */
    public List<Map<String, Object>> searchVectors(List<Float> queryEmbedding, String collection, int topK) {
        try {
            String embeddingStr = vectorToString(queryEmbedding);

            String sql = String.format("""
                SELECT id, content, metadata,
                       1 - (embedding <=> '%s') AS score
                FROM embeddings
                WHERE collection = ?
                ORDER BY embedding <=> '%s'
                LIMIT ?
                """, embeddingStr, embeddingStr);

            List<Map<String, Object>> results = getJdbcTemplate().queryForList(sql, collection, topK);

            log.debug("PGVector search returned {} results from collection {}", results.size(), collection);
            return results;

        } catch (Exception e) {
            log.error("PGVector search failed: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 插入向量 (批量优化)
     */
    public int insertVectors(String collection, List<Map<String, Object>> documents, List<List<Float>> embeddings) {
        try {
            if (documents == null || documents.isEmpty()) {
                return 0;
            }

            String sql = """
                INSERT INTO embeddings (id, collection, content, metadata, embedding)
                VALUES (?, ?, ?, ?::jsonb, ?::vector)
                """;

            // 使用 batchUpdate 批量插入以提高性能
            List<Object[]> batchArgs = new ArrayList<>(documents.size());
            for (int i = 0; i < documents.size(); i++) {
                Map<String, Object> doc = documents.get(i);
                List<Float> embedding = embeddings.get(i);
                batchArgs.add(new Object[]{
                    UUID.randomUUID().toString(),
                    collection,
                    (String) doc.getOrDefault("content", ""),
                    doc.getOrDefault("metadata", "{}").toString(),
                    vectorToString(embedding)
                });
            }

            int[] results = getJdbcTemplate().batchUpdate(sql, batchArgs);
            int totalInserted = Arrays.stream(results).sum();

            log.info("Batch inserted {} vectors into collection {}", totalInserted, collection);
            return totalInserted;

        } catch (Exception e) {
            log.error("PGVector insert failed: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to insert vectors", e);
        }
    }

    /**
     * 删除向量
     */
    public boolean deleteVectors(String collection, List<String> ids) {
        try {
            if (ids == null || ids.isEmpty()) {
                return true;
            }

            String placeholders = ids.stream()
                    .map(id -> "'" + id + "'")
                    .collect(Collectors.joining(","));

            String sql = String.format(
                    "DELETE FROM embeddings WHERE collection = ? AND id IN (%s)",
                    placeholders);

            int deleted = getJdbcTemplate().update(sql, collection);
            log.info("Deleted {} vectors from collection {}", deleted, collection);
            return true;

        } catch (Exception e) {
            log.error("PGVector delete failed: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 创建 Collection (表)
     */
    public void createCollection(String collection) {
        try {
            String sql = String.format("""
                CREATE TABLE IF NOT EXISTS embeddings (
                    id UUID PRIMARY KEY,
                    collection VARCHAR(255) NOT NULL,
                    content TEXT,
                    metadata JSONB,
                    embedding VECTOR(%d),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """, embeddingDim);

            getJdbcTemplate().execute(sql);

            // 创建索引
            String indexSql = String.format("""
                CREATE INDEX IF NOT EXISTS idx_embeddings_%s
                ON embeddings USING ivfflat (embedding vector_cosine_ops)
                WITH (lists = 100)
                WHERE collection = '%s'
                """, collection, collection);

            try {
                getJdbcTemplate().execute(indexSql);
            } catch (Exception e) {
                log.debug("Index creation skipped (may already exist): {}", e.getMessage());
            }

            log.info("Created PGVector collection/table: {}", collection);

        } catch (Exception e) {
            log.error("Failed to create collection {}: {}", collection, e.getMessage(), e);
            throw new RuntimeException("Failed to create collection", e);
        }
    }

    /**
     * 检查 Collection 是否存在
     */
    public boolean collectionExists(String collection) {
        try {
            String sql = """
                SELECT EXISTS (
                    SELECT FROM information_schema.tables
                    WHERE table_name = 'embeddings'
                    AND table_schema = 'public'
                )
                """;
            Boolean exists = getJdbcTemplate().queryForObject(sql, Boolean.class);
            return exists != null && exists;
        } catch (Exception e) {
            log.error("Failed to check collection existence: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 初始化表 (如果不存在)
     */
    public void initializeSchema() {
        if (!collectionExists("embeddings")) {
            createCollection("embeddings");
        }
    }

    /**
     * 将 float 列表转换为 pgvector 格式字符串
     */
    private String vectorToString(List<Float> embedding) {
        return "[" + embedding.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",")) + "]";
    }
}
