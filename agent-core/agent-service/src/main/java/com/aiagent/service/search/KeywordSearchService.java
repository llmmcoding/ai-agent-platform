package com.aiagent.service.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 关键词搜索服务 - 向量库降级时的备用检索方案
 * 使用 Redis Hash 存储文档内容，支持关键词匹配
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeywordSearchService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String INDEX_PREFIX = "kb:keyword:";

    @Value("${aiagent.keyword-search.ttl-hours:24}")
    private int ttlHours;

    @Value("${aiagent.keyword-search.max-results:20}")
    private int maxResults;

    @PostConstruct
    public void init() {
        log.info("KeywordSearchService initialized with TTL={}h, maxResults={}", ttlHours, maxResults);
    }

    /**
     * 创建索引
     */
    public void createIndex(String collection) {
        log.debug("Keyword index created for collection: {}", collection);
    }

    /**
     * 索引文档
     */
    public void indexDocument(String collection, String id, String content, Map<String, Object> metadata) {
        if (content == null || content.isBlank()) {
            return;
        }

        try {
            String key = INDEX_PREFIX + collection;
            String field = id;

            // 存储文档内容
            Map<String, String> docData = new HashMap<>();
            docData.put("content", content);
            docData.put("metadata", objectMapper.writeValueAsString(metadata));

            // 分词后存储 (简单按空格分词，实际生产应使用 IKAnalyzer 等)
            List<String> keywords = extractKeywords(content);
            docData.put("keywords", String.join(" ", keywords));

            redisTemplate.opsForHash().putAll(key + ":" + field, docData);
            redisTemplate.expire(key + ":" + field, ttlHours, TimeUnit.HOURS);

            // 同时更新 collection 文档列表
            redisTemplate.opsForSet().add(key + ":ids", id);

            log.debug("Indexed document {} in collection {} with {} keywords",
                    id, collection, keywords.size());

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize metadata for doc {}: {}", id, e.getMessage());
        }
    }

    /**
     * 删除文档
     */
    public void deleteDocument(String collection, String id) {
        String key = INDEX_PREFIX + collection;
        redisTemplate.opsForHash().delete(key + ":" + id);
        redisTemplate.opsForSet().remove(key + ":ids", id);
        log.debug("Deleted document {} from collection {}", id, collection);
    }

    /**
     * 关键词搜索
     */
    public List<Map<String, Object>> search(String collection, String query, int topK) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        String key = INDEX_PREFIX + collection;
        Set<String> docIds = redisTemplate.opsForSet().members(key + ":ids");

        if (docIds == null || docIds.isEmpty()) {
            log.debug("No documents found in keyword index for collection: {}", collection);
            return Collections.emptyList();
        }

        // 提取查询关键词
        List<String> queryKeywords = extractKeywords(query);

        // 简单评分: 匹配关键词数量 / 文档关键词总数
        Map<String, Integer> scores = new HashMap<>();

        for (String docId : docIds) {
            String docKey = key + ":" + docId;
            String keywords = (String) redisTemplate.opsForHash().get(docKey, "keywords");

            if (keywords == null) {
                continue;
            }

            Set<String> docKeywordSet = new HashSet<>(Arrays.asList(keywords.split("\\s+")));
            int matchCount = 0;

            for (String queryKw : queryKeywords) {
                for (String docKw : docKeywordSet) {
                    if (docKw.contains(queryKw.toLowerCase()) || queryKw.contains(docKw.toLowerCase())) {
                        matchCount++;
                        break;
                    }
                }
            }

            if (matchCount > 0) {
                // 归一化分数
                double score = (double) matchCount / Math.max(docKeywordSet.size(), queryKeywords.size());
                scores.put(docId, (int) (score * 100));
            }
        }

        // 按分数排序，返回 topK
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(topK > 0 ? topK : maxResults)
                .map(entry -> {
                    String docId = entry.getKey();
                    String docKey = key + ":" + docId;

                    Map<String, Object> result = new HashMap<>();
                    result.put("id", docId);
                    result.put("score", entry.getValue() / 100.0);
                    result.put("source", "keyword_search");

                    try {
                        String content = (String) redisTemplate.opsForHash().get(docKey, "content");
                        String metadataJson = (String) redisTemplate.opsForHash().get(docKey, "metadata");
                        result.put("content", content);
                        if (metadataJson != null) {
                            result.put("metadata", objectMapper.readValue(metadataJson, Map.class));
                        }
                    } catch (Exception e) {
                        log.warn("Failed to load document {} content: {}", docId, e.getMessage());
                    }

                    return result;
                })
                .collect(Collectors.toList());
    }

    /**
     * 提取关键词 (简单实现，生产环境应使用分词器)
     */
    private List<String> extractKeywords(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        // 简单分词: 按空格和标点分割，转小写
        String[] tokens = text.toLowerCase()
                .split("[\\s\\p{Punct}]+");

        // 过滤停用词和过短的词
        Set<String> stopWords = Set.of(
                "的", "了", "和", "是", "在", "我", "有", "个", "人", "这",
                "the", "a", "an", "and", "or", "but", "in", "on", "at",
                "to", "for", "of", "with", "by", "is", "are", "was", "were"
        );

        return Arrays.stream(tokens)
                .filter(t -> t.length() > 1)
                .filter(t -> !stopWords.contains(t))
                .distinct()
                .limit(50) // 限制关键词数量
                .collect(Collectors.toList());
    }

    /**
     * 获取索引统计信息
     */
    public Map<String, Object> getIndexStats(String collection) {
        String key = INDEX_PREFIX + collection;
        Set<String> docIds = redisTemplate.opsForSet().members(key + ":ids");

        Map<String, Object> stats = new HashMap<>();
        stats.put("collection", collection);
        stats.put("documentCount", docIds != null ? docIds.size() : 0);
        stats.put("ttlHours", ttlHours);

        return stats;
    }
}
