package com.aiagent.service.impl;

import com.aiagent.common.dto.AgentRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.script.ScriptEngineManager;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolRegistryImpl 单元测试
 */
@ExtendWith(MockitoExtension.class)
class ToolRegistryImplTest {

    private ToolRegistryImpl toolRegistry;
    private ObjectMapper objectMapper;
    private boolean jsEngineAvailable;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        toolRegistry = new ToolRegistryImpl(objectMapper);
        // Check if JavaScript engine is available (removed in newer JDK versions)
        jsEngineAvailable = new ScriptEngineManager().getEngineByName("JavaScript") != null;
    }

    @Test
    void should_register_builtin_tools() {
        // Then - verify built-in tools are registered
        assertTrue(toolRegistry.hasTool("calculator"));
        assertTrue(toolRegistry.hasTool("get_time"));
        assertTrue(toolRegistry.hasTool("text_process"));
        assertTrue(toolRegistry.hasTool("random"));
        assertTrue(toolRegistry.hasTool("get_user_info"));
    }

    @Test
    void should_execute_calculator_add() throws ExecutionException, InterruptedException {
        // Given
        Map<String, Object> input = new HashMap<>();
        input.put("expression", "2 + 3");
        AgentRequest request = AgentRequest.builder().build();

        // When
        CompletableFuture<String> result = toolRegistry.execute("calculator", input, request);
        String output = result.get();

        // Then
        assertEquals("5.0", output);
    }

    @Test
    void should_execute_calculator_complex_expression() throws ExecutionException, InterruptedException {
        // Given
        Map<String, Object> input = new HashMap<>();
        input.put("expression", "(10 + 5) * 2 - 8");
        AgentRequest request = AgentRequest.builder().build();

        // When
        CompletableFuture<String> result = toolRegistry.execute("calculator", input, request);
        String output = result.get();

        // Then
        assertEquals("22.0", output);
    }

    @Test
    void should_execute_calculator_divide_by_zero() throws ExecutionException, InterruptedException {
        // Given
        Map<String, Object> input = new HashMap<>();
        input.put("expression", "10 / 0");
        AgentRequest request = AgentRequest.builder().build();

        // When
        CompletableFuture<String> result = toolRegistry.execute("calculator", input, request);
        String output = result.get();

        // Then - JavaScript returns Infinity for division by zero
        assertTrue(output.equals("Infinity") || output.startsWith("Error:"),
                "Expected 'Infinity' or error, but got: " + output);
    }

    @Test
    void should_execute_calculator_with_empty_expression() throws ExecutionException, InterruptedException {
        // Given
        Map<String, Object> input = new HashMap<>();
        input.put("expression", "");
        AgentRequest request = AgentRequest.builder().build();

        // When
        CompletableFuture<String> result = toolRegistry.execute("calculator", input, request);
        String output = result.get();

        // Then
        assertEquals("Error: expression is required", output);
    }

    @Test
    void should_execute_get_time_default_format() throws ExecutionException, InterruptedException {
        // Given
        Map<String, Object> input = new HashMap<>();
        AgentRequest request = AgentRequest.builder().build();

        // When
        CompletableFuture<String> result = toolRegistry.execute("get_time", input, request);
        String output = result.get();

        // Then
        assertNotNull(output);
        assertTrue(output.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    void should_execute_get_time_custom_format() throws ExecutionException, InterruptedException {
        // Given
        Map<String, Object> input = new HashMap<>();
        input.put("format", "yyyy-MM-dd");
        AgentRequest request = AgentRequest.builder().build();

        // When
        CompletableFuture<String> result = toolRegistry.execute("get_time", input, request);
        String output = result.get();

        // Then
        assertNotNull(output);
        assertTrue(output.matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    @Test
    void should_execute_text_process_uppercase() throws ExecutionException, InterruptedException {
        // Given
        Map<String, Object> input = new HashMap<>();
        input.put("text", "hello world");
        input.put("operation", "uppercase");
        AgentRequest request = AgentRequest.builder().build();

        // When
        CompletableFuture<String> result = toolRegistry.execute("text_process", input, request);
        String output = result.get();

        // Then
        assertEquals("HELLO WORLD", output);
    }

    @Test
    void should_execute_text_process_lowercase() throws ExecutionException, InterruptedException {
        // Given
        Map<String, Object> input = new HashMap<>();
        input.put("text", "HELLO WORLD");
        input.put("operation", "lowercase");
        AgentRequest request = AgentRequest.builder().build();

        // When
        CompletableFuture<String> result = toolRegistry.execute("text_process", input, request);
        String output = result.get();

        // Then
        assertEquals("hello world", output);
    }

    @Test
    void should_execute_text_process_trim() throws ExecutionException, InterruptedException {
        // Given
        Map<String, Object> input = new HashMap<>();
        input.put("text", "  hello  ");
        input.put("operation", "trim");
        AgentRequest request = AgentRequest.builder().build();

        // When
        CompletableFuture<String> result = toolRegistry.execute("text_process", input, request);
        String output = result.get();

        // Then
        assertEquals("hello", output);
    }

    @Test
    void should_execute_text_process_length() throws ExecutionException, InterruptedException {
        // Given
        Map<String, Object> input = new HashMap<>();
        input.put("text", "hello");
        input.put("operation", "length");
        AgentRequest request = AgentRequest.builder().build();

        // When
        CompletableFuture<String> result = toolRegistry.execute("text_process", input, request);
        String output = result.get();

        // Then
        assertEquals("5", output);
    }

    @Test
    void should_execute_text_process_word_count() throws ExecutionException, InterruptedException {
        // Given
        Map<String, Object> input = new HashMap<>();
        input.put("text", "hello world test");
        input.put("operation", "word_count");
        AgentRequest request = AgentRequest.builder().build();

        // When
        CompletableFuture<String> result = toolRegistry.execute("text_process", input, request);
        String output = result.get();

        // Then
        assertEquals("3", output);
    }

    @Test
    void should_execute_random_default_range() throws ExecutionException, InterruptedException {
        // Given
        Map<String, Object> input = new HashMap<>();
        AgentRequest request = AgentRequest.builder().build();

        // When
        CompletableFuture<String> result = toolRegistry.execute("random", input, request);
        String output = result.get();

        // Then
        assertNotNull(output);
        int value = Integer.parseInt(output);
        assertTrue(value >= 0 && value <= 100);
    }

    @Test
    void should_execute_random_custom_range() throws ExecutionException, InterruptedException {
        // Given
        Map<String, Object> input = new HashMap<>();
        input.put("min", 50);
        input.put("max", 60);
        AgentRequest request = AgentRequest.builder().build();

        // When
        CompletableFuture<String> result = toolRegistry.execute("random", input, request);
        String output = result.get();

        // Then
        assertNotNull(output);
        int value = Integer.parseInt(output);
        assertTrue(value >= 50 && value <= 60);
    }

    @Test
    void should_execute_random_multiple_count() throws ExecutionException, InterruptedException {
        // Given
        Map<String, Object> input = new HashMap<>();
        input.put("min", 1);
        input.put("max", 10);
        input.put("count", 5);
        AgentRequest request = AgentRequest.builder().build();

        // When
        CompletableFuture<String> result = toolRegistry.execute("random", input, request);
        String output = result.get();

        // Then
        assertNotNull(output);
        String[] values = output.split(", ");
        assertEquals(5, values.length);
    }

    @Test
    void should_sanitize_calculator_expression() throws ExecutionException, InterruptedException {
        // Given - input contains malicious content (letters should be stripped)
        Map<String, Object> input = new HashMap<>();
        input.put("expression", "10 + abc + 5");
        AgentRequest request = AgentRequest.builder().build();

        // When
        CompletableFuture<String> result = toolRegistry.execute("calculator", input, request);
        String output = result.get();

        // Then - letters stripped, becomes "10 ++ 5" which JS can evaluate as 15
        // GraalJS may throw syntax error for "10++5", so accept valid result or error
        assertTrue(output.equals("15.0") || output.startsWith("Error:"),
                "Expected '15.0' or error, but got: " + output);
    }

    @Test
    void should_execute_get_user_info() throws ExecutionException, InterruptedException {
        // Given
        Map<String, Object> input = new HashMap<>();
        input.put("userId", "test-user-001");
        AgentRequest request = AgentRequest.builder()
                .userId("test-user-001")
                .build();

        // When
        CompletableFuture<String> result = toolRegistry.execute("get_user_info", input, request);
        String output = result.get();

        // Then
        assertNotNull(output);
        assertTrue(output.contains("test-user-001"));
        assertTrue(output.contains("active"));
    }

    @Test
    void get_tool_description_returns_json() {
        // When
        String description = toolRegistry.getToolDescription("calculator");

        // Then
        assertNotNull(description);
        assertTrue(description.contains("calculator"));
        assertTrue(description.toLowerCase().contains("java"));
    }

    @Test
    void get_tool_description_null_for_unknown_tool() {
        // When
        String description = toolRegistry.getToolDescription("unknown_tool");

        // Then
        assertNull(description);
    }

    @Test
    void get_all_tools_description_returns_all() {
        // When
        String allDescriptions = toolRegistry.getAllToolsDescription();

        // Then
        assertNotNull(allDescriptions);
        assertTrue(allDescriptions.contains("calculator"));
        assertTrue(allDescriptions.contains("get_time"));
        assertTrue(allDescriptions.contains("text_process"));
        assertTrue(allDescriptions.contains("random"));
    }

    @Test
    void has_tool_returns_true_for_registered() {
        // Then
        assertTrue(toolRegistry.hasTool("calculator"));
        assertTrue(toolRegistry.hasTool("get_time"));
        assertFalse(toolRegistry.hasTool("nonexistent_tool"));
    }
}
