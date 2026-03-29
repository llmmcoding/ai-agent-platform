package com.aiagent.service.streaming;

import com.aiagent.common.dto.AgentRequest;
import reactor.core.publisher.Flux;

/**
 * 流式 LLM 服务接口
 */
public interface StreamingLLMService {

    /**
     * 流式生成响应
     *
     * @param prompt  提示词
     * @param request Agent 请求
     * @return token 流
     */
    Flux<String> streamGenerate(String prompt, AgentRequest request);

    /**
     * 流式生成响应 (SSE 格式)
     *
     * @param prompt  提示词
     * @param request Agent 请求
     * @return SSE 格式的 token 流
     */
    Flux<ServerSentEvent> streamGenerateSSE(String prompt, AgentRequest request);

    /**
     * Server-Sent Event 数据结构
     */
    record ServerSentEvent(String id, String event, String data) {
        public static ServerSentEvent of(String data) {
            return new ServerSentEvent(null, "message", data);
        }

        public static ServerSentEvent done() {
            return new ServerSentEvent(null, "done", "");
        }

        public static ServerSentEvent error(String message) {
            return new ServerSentEvent(null, "error", message);
        }

        public String toSSEFormat() {
            StringBuilder sb = new StringBuilder();
            if (id != null) {
                sb.append("id: ").append(id).append("\n");
            }
            if (event != null) {
                sb.append("event: ").append(event).append("\n");
            }
            if (data != null) {
                sb.append("data: ").append(data).append("\n");
            }
            sb.append("\n");
            return sb.toString();
        }
    }
}
