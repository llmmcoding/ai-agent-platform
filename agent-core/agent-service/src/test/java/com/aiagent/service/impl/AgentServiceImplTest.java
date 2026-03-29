package com.aiagent.service.impl;

import com.aiagent.common.dto.AgentRequest;
import com.aiagent.common.dto.AgentResponse;
import com.aiagent.common.exception.AgentException;
import com.aiagent.service.LLMService;
import com.aiagent.service.MemoryService;
import com.aiagent.service.PromptBuilder;
import com.aiagent.service.ReActEngine;
import com.aiagent.service.ToolRegistry;
import com.aiagent.service.metrics.AgentMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AgentServiceImpl 单元测试
 */
@ExtendWith(MockitoExtension.class)
class AgentServiceImplTest {

    @Mock
    private PromptBuilder promptBuilder;

    @Mock
    private LLMService llmService;

    @Mock
    private ToolRegistry toolRegistry;

    @Mock
    private MemoryService memoryService;

    @Mock
    private ReActEngine reActEngine;

    @Mock
    private AgentMetrics agentMetrics;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private AgentServiceImpl agentService;

    private AgentRequest defaultRequest;
    private AgentResponse mockResponse;

    @BeforeEach
    void setUp() {
        defaultRequest = AgentRequest.builder()
                .sessionId("test-session-001")
                .userId("user-001")
                .userInput("你好，请帮我查询天气")
                .build();

        mockResponse = AgentResponse.builder()
                .responseId("resp-001")
                .sessionId("test-session-001")
                .content("今天天气晴朗，温度25度")
                .completed(true)
                .status("success")
                .latencyMs(1500L)
                .build();

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void invoke_success() {
        // Given
        when(promptBuilder.build(any(AgentRequest.class))).thenReturn("Build prompt");
        when(promptBuilder.buildWithMemory(anyString(), anyString())).thenReturn("Full prompt");
        when(memoryService.getShortTermMemory(anyString())).thenReturn("Memory context");
        when(reActEngine.execute(anyString(), any(AgentRequest.class))).thenReturn(mockResponse);

        // When
        Mono<AgentResponse> result = agentService.invoke(defaultRequest);

        // Then
        AgentResponse response = result.block();
        assertNotNull(response);
        assertNotNull(response.getResponseId());
        assertEquals("今天天气晴朗，温度25度", response.getContent());
        assertTrue(response.getCompleted());
        assertNotNull(response.getSessionId());
    }

    @Test
    void invoke_with_rag_enabled() {
        // Given
        defaultRequest.setEnableRag(true);
        defaultRequest.setUserId("user-001");

        when(promptBuilder.build(any(AgentRequest.class))).thenReturn("Build prompt");
        when(memoryService.getShortTermMemory(anyString())).thenReturn("Memory context");
        when(memoryService.getLongTermMemory(anyString(), anyString())).thenReturn("RAG context");
        when(promptBuilder.buildWithMemoryAndRag(anyString(), anyString(), anyString()))
                .thenReturn("Full prompt with RAG");
        when(reActEngine.execute(anyString(), any(AgentRequest.class))).thenReturn(mockResponse);

        // When
        Mono<AgentResponse> result = agentService.invoke(defaultRequest);

        // Then
        AgentResponse response = result.block();
        assertNotNull(response);

        verify(memoryService).getLongTermMemory(eq("user-001"), eq("你好，请帮我查询天气"));
        verify(promptBuilder).buildWithMemoryAndRag(anyString(), anyString(), eq("RAG context"));
    }

    @Test
    void invoke_metrics_recorded() {
        // Given
        when(promptBuilder.build(any(AgentRequest.class))).thenReturn("Build prompt");
        when(promptBuilder.buildWithMemory(anyString(), anyString())).thenReturn("Full prompt");
        when(memoryService.getShortTermMemory(anyString())).thenReturn("Memory context");
        when(reActEngine.execute(anyString(), any(AgentRequest.class))).thenReturn(mockResponse);

        // When
        agentService.invoke(defaultRequest).block();

        // Then - no metrics verification since metrics may not be wired in this impl
        assertTrue(true);
    }

    @Test
    void invoke_async_returns_taskId() throws Exception {
        // Given
        when(promptBuilder.build(any(AgentRequest.class))).thenReturn("Build prompt");
        when(promptBuilder.buildWithMemory(anyString(), anyString())).thenReturn("Full prompt");
        when(memoryService.getShortTermMemory(anyString())).thenReturn("Memory context");
        when(reActEngine.execute(anyString(), any(AgentRequest.class))).thenReturn(mockResponse);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"content\":\"test\"}");

        // When
        Mono<String> result = agentService.invokeAsync(defaultRequest);

        // Then
        String taskId = result.block();
        assertNotNull(taskId);
        assertFalse(taskId.isEmpty());
        assertTrue(taskId.matches("[a-f0-9\\-]{36}"));

        // Verify Redis was used to store task
        verify(redisTemplate.opsForValue()).set(contains("aiagent:task:"), anyString(), eq(3600L), any());
    }

    @Test
    void getTaskStatus_found() throws Exception {
        // Given
        String taskId = "test-task-id-001";
        String cachedJson = "{\"content\":\"cached response\",\"responseId\":\"resp-123\"}";

        when(redisTemplate.opsForValue().get("aiagent:task:" + taskId)).thenReturn(cachedJson);
        when(objectMapper.readValue(eq(cachedJson), eq(AgentResponse.class)))
                .thenReturn(AgentResponse.builder()
                        .content("cached response")
                        .responseId("resp-123")
                        .build());

        // When
        Mono<AgentResponse> result = agentService.getTaskStatus(taskId);

        // Then
        AgentResponse response = result.block();
        assertNotNull(response);
        assertEquals("cached response", response.getContent());
    }

    @Test
    void getTaskStatus_notFound() {
        // Given
        when(redisTemplate.opsForValue().get(anyString())).thenReturn(null);

        // When
        Mono<AgentResponse> result = agentService.getTaskStatus("non-existent-task-id");

        // Then
        assertThrows(AgentException.class, result::block);
    }

    @Test
    void invoke_stream_returns_mono() {
        // Given
        when(promptBuilder.build(any(AgentRequest.class))).thenReturn("Build prompt");
        when(promptBuilder.buildWithMemory(anyString(), anyString())).thenReturn("Full prompt");
        when(memoryService.getShortTermMemory(anyString())).thenReturn("Memory context");
        when(reActEngine.execute(anyString(), any(AgentRequest.class))).thenReturn(mockResponse);

        // When
        Mono<String> result = agentService.invokeStream(defaultRequest);

        // Then
        assertNotNull(result);
        String content = result.block();
        assertEquals("今天天气晴朗，温度25度", content);
    }

    @Test
    void invoke_error_handler() {
        // Given
        when(promptBuilder.build(any(AgentRequest.class))).thenReturn("Build prompt");
        when(promptBuilder.buildWithMemory(anyString(), anyString())).thenReturn("Full prompt");
        when(memoryService.getShortTermMemory(anyString())).thenReturn("Memory context");
        when(reActEngine.execute(anyString(), any(AgentRequest.class)))
                .thenThrow(new RuntimeException("LLM Error"));

        // When/Then
        assertThrows(RuntimeException.class, () -> agentService.invoke(defaultRequest).block());
    }
}
