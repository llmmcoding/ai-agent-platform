package com.aiagent.service.memory;

import com.aiagent.service.embedding.EmbeddingService;
import com.aiagent.service.vector.VectorStoreFactory;
import com.aiagent.service.vector.VectorStoreFactory.VectorSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * EnhancedMemoryServiceImpl 单元测试
 */
@ExtendWith(MockitoExtension.class)
class EnhancedMemoryServiceImplTest {

    @Mock
    private VectorStoreFactory vectorStoreFactory;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private VectorSearchService vectorSearchService;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private EnhancedMemoryServiceImpl memoryService;

    @BeforeEach
    void setUp() {
        memoryService = new EnhancedMemoryServiceImpl(
                vectorStoreFactory, redisTemplate, objectMapper, embeddingService
        );

        // Inject @Value fields using ReflectionTestUtils
        ReflectionTestUtils.setField(memoryService, "episodicCollection", "episodic_memory");
        ReflectionTestUtils.setField(memoryService, "factualCollection", "factual_memory");

        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(vectorStoreFactory.getSearchService()).thenReturn(vectorSearchService);
    }

    @Test
    void save_episodic_memory() {
        // Given
        MemoryEntry entry = MemoryEntry.builder()
                .type(MemoryType.EPISODIC)
                .userId("user-001")
                .content("用户问了关于天气的问题")
                .sessionId("session-001")
                .build();

        when(embeddingService.getEmbedding(anyString())).thenReturn(Collections.nCopies(1536, 0.0f));
        when(vectorSearchService.insertVectors(anyString(), anyList(), anyList())).thenReturn(1);

        // When
        memoryService.save(entry);

        // Then
        verify(vectorSearchService).insertVectors(
                eq("episodic_memory"),
                anyList(),
                anyList()
        );
        verify(valueOperations).increment(contains("user-001:EPISODIC"));
    }

    @Test
    void save_factual_memory_updates_existing() {
        // Given
        MemoryEntry existingEntry = MemoryEntry.builder()
                .id("existing-id-001")
                .type(MemoryType.FACTUAL)
                .userId("user-001")
                .entityKey("name")
                .entityValue("张三")
                .content("name: 张三")
                .build();

        MemoryEntry newEntry = MemoryEntry.builder()
                .type(MemoryType.FACTUAL)
                .userId("user-001")
                .entityKey("name")
                .entityValue("李四")
                .content("name: 李四")
                .build();

        when(embeddingService.getEmbedding(anyString())).thenReturn(Collections.nCopies(1536, 0.0f));
        when(vectorSearchService.searchVectors(anyList(), anyString(), anyInt()))
                .thenReturn(List.of(Map.of(
                        "id", "existing-id-001",
                        "content", "name: 张三",
                        "type", "FACTUAL",
                        "userId", "user-001",
                        "entityKey", "name",
                        "entityValue", "张三"
                )));
        when(vectorSearchService.insertVectors(anyString(), anyList(), anyList())).thenReturn(1);

        // When
        memoryService.save(newEntry);

        // Then
        verify(vectorSearchService).insertVectors(
                eq("factual_memory"),
                argThat(docs -> {
                    // Should have the existing ID for update
                    Map<String, Object> doc = (Map<String, Object>) docs.get(0);
                    return "existing-id-001".equals(doc.get("id"));
                }),
                anyList()
        );
    }

    @Test
    void save_preference_memory() {
        // Given
        MemoryEntry entry = MemoryEntry.builder()
                .type(MemoryType.PREFERENCE)
                .userId("user-001")
                .entityKey("language")
                .entityValue("中文")
                .content("用户偏好: language = 中文")
                .build();

        when(embeddingService.getEmbedding(anyString())).thenReturn(Collections.nCopies(1536, 0.0f));
        when(vectorSearchService.insertVectors(anyString(), anyList(), anyList())).thenReturn(1);

        // When
        memoryService.save(entry);

        // Then
        verify(vectorSearchService).insertVectors(
                eq("preference_memory"),
                anyList(),
                anyList()
        );
    }

    @Test
    void retrieve_by_type() {
        // Given
        String userId = "user-001";
        String query = "天气";
        MemoryType type = MemoryType.EPISODIC;

        List<Map<String, Object>> mockResults = List.of(
                Map.of(
                        "id", "mem-001",
                        "content", "用户问天气",
                        "type", "EPISODIC",
                        "userId", "user-001"
                )
        );

        when(embeddingService.getEmbedding(anyString())).thenReturn(Collections.nCopies(1536, 0.0f));
        when(vectorSearchService.searchVectors(anyList(), anyString(), anyInt())).thenReturn(mockResults);

        // When
        List<MemoryEntry> results = memoryService.retrieve(userId, query, type);

        // Then
        assertEquals(1, results.size());
        assertEquals("mem-001", results.get(0).getId());
        assertEquals("用户问天气", results.get(0).getContent());
    }

    @Test
    void set_and_get_preference() throws Exception {
        // Given
        String userId = "user-001";
        String key = "language";
        Object value = "中文";
        String serializedValue = "\"中文\"";

        // Mock objectMapper to serialize the value
        when(objectMapper.writeValueAsString(value)).thenReturn(serializedValue);
        // Mock put to do nothing (just verify it was called)
        doNothing().when(hashOperations).put(anyString(), eq(key), eq(serializedValue));
        when(hashOperations.get(anyString(), eq(key))).thenReturn(serializedValue);
        when(objectMapper.readValue(eq(serializedValue), eq(Object.class))).thenReturn("中文");

        // When
        memoryService.setPreference(userId, key, value);
        Object result = memoryService.getPreference(userId, key);

        // Then
        verify(hashOperations).put(contains("ai:memory:preference:user-001"), eq(key), eq(serializedValue));
        assertEquals("中文", result);
    }

    @Test
    void delete_memory() {
        // Given
        String memoryId = "user-001:EPISODIC:mem-001";

        when(vectorSearchService.deleteVectors(anyString(), anyList())).thenReturn(true);

        // When
        memoryService.delete(memoryId);

        // Then
        verify(vectorSearchService).deleteVectors(anyString(), eq(List.of(memoryId)));
        verify(valueOperations).decrement(contains("user-001:EPISODIC"));
    }

    @Test
    void clear_user_memory() {
        // Given
        String userId = "user-001";

        when(redisTemplate.delete(anyString())).thenReturn(true);

        // When
        memoryService.clearUserMemory(userId);

        // Then - implementation only deletes preference key from Redis
        // (episodic/factual are in Milvus and not deleted in this method)
        verify(redisTemplate).delete(contains("ai:memory:preference:user-001"));
        verify(redisTemplate, times(1)).delete(anyString());
    }

    @Test
    void get_memory_stats() {
        // Given
        String userId = "user-001";

        when(valueOperations.get(contains("EPISODIC"))).thenReturn("10");
        when(valueOperations.get(contains("FACTUAL"))).thenReturn("5");
        when(valueOperations.get(contains("PREFERENCE"))).thenReturn("3");

        // When
        Map<String, Integer> stats = memoryService.getMemoryStats(userId);

        // Then
        assertEquals(10, stats.get("EPISODIC"));
        assertEquals(5, stats.get("FACTUAL"));
        assertEquals(3, stats.get("PREFERENCE"));
    }

    @Test
    void save_with_null_userId_does_nothing() {
        // Given
        MemoryEntry entry = MemoryEntry.builder()
                .type(MemoryType.EPISODIC)
                .userId(null)
                .content("test content")
                .build();

        // When
        memoryService.save(entry);

        // Then
        verify(vectorSearchService, never()).insertVectors(anyString(), anyList(), anyList());
    }

    @Test
    void delete_with_null_id_does_nothing() {
        // When
        memoryService.delete(null);

        // Then
        verify(vectorSearchService, never()).deleteVectors(anyString(), anyList());
    }
}
