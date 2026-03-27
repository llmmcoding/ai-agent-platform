package com.aiagent.common;

/**
 * AI Agent 平台常量定义
 */
public class Constants {

    private Constants() {
    }

    // ==================== LLM Provider ====================
    public static final class LLM {
        public static final String OPENAI = "openai";
        public static final String ANTHROPIC = "anthropic";
        public static final String LOCAL = "local";

        private LLM() {
        }
    }

    // ==================== Kafka Topic ====================
    public static final class KafkaTopic {
        public static final String TOOL_EXECUTE = "ai-agent-tool-execute";
        public static final String TOOL_RESULT = "ai-agent-tool-result";
        public static final String RAG_QUERY = "ai-agent-rag-query";
        public static final String RAG_RESULT = "ai-agent-rag-result";
        public static final String MEMORY_SUMMARY = "ai-agent-memory-summary";
        public static final String VECTOR_WRITE = "ai-agent-vector-write";  // 向量批量写入

        private KafkaTopic() {
        }
    }

    // ==================== Redis Key ====================
    public static final class RedisKey {
        public static final String SESSION_PREFIX = "ai:session:";
        public static final String MEMORY_PREFIX = "ai:memory:";
        public static final String RATE_LIMIT_PREFIX = "ai:ratelimit:";
        public static final String LLM_CACHE_PREFIX = "ai:llm:cache:";

        public static final int SESSION_TTL = 3600; // 1小时
        public static final int MEMORY_TTL = 86400; // 24小时

        private RedisKey() {
        }
    }

    // ==================== Agent Status ====================
    public static final class AgentStatus {
        public static final int IDLE = 0;
        public static final int RUNNING = 1;
        public static final int WAITING_TOOL = 2;
        public static final int COMPLETED = 3;
        public static final int ERROR = 4;

        private AgentStatus() {
        }
    }

    // ==================== ReAct ====================
    public static final class ReAct {
        public static final String THOUGHT = "thought";
        public static final String ACTION = "action";
        public static final String ACTION_INPUT = "action_input";
        public static final String OBSERVATION = "observation";
        public static final String FINAL = "final";
        public static final int MAX_ITERATIONS = 10;

        private ReAct() {
        }
    }

    // ==================== Tool ====================
    public static final class Tool {
        public static final String JAVA = "java";
        public static final String PYTHON = "python";
        public static final String EXTERNAL = "external";

        private Tool() {
        }
    }
}
