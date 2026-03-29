# AI Agent 平台使用指南

## 服务地址

- **API 地址**: http://localhost:8080
- **健康检查**: http://localhost:8080/actuator/health
- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **Prometheus 指标**: http://localhost:8080/actuator/prometheus

---

## 1. Agent API

### 1.1 同步调用
```bash
curl -X POST http://localhost:8080/api/v1/agent/invoke \
  -H "Content-Type: application/json" \
  -d '{
    "userInput": "你好，帮我算一下 123 + 456",
    "sessionId": "session-001",
    "enabledTools": ["calculator"]
  }'
```

### 1.2 流式调用
```bash
curl -X POST http://localhost:8080/api/v1/agent/invoke/stream \
  -H "Content-Type: application/json" \
  -d '{
    "userInput": "写一首关于春天的诗",
    "sessionId": "session-002"
  }'
```

### 1.3 异步调用
```bash
curl -X POST http://localhost:8080/api/v1/agent/invoke/async \
  -H "Content-Type: application/json" \
  -d '{
    "userInput": "帮我查询今天的天气",
    "sessionId": "session-003"
  }'

# 返回 taskId，然后查询状态
curl -X GET http://localhost:8080/api/v1/agent/task/{taskId}
```

---

## 2. 内置工具

### 2.1 Calculator (计算器)
```bash
curl -X POST http://localhost:8080/api/v1/agent/invoke \
  -H "Content-Type: application/json" \
  -d '{
    "userInput": "请用计算器算一下 (100 + 200) * 3 = ?",
    "enabledTools": ["calculator"]
  }'
```

### 2.2 Get Time (获取时间)
```bash
curl -X POST http://localhost:8080/api/v1/agent/invoke \
  -H "Content-Type: application/json" \
  -d '{
    "userInput": "现在几点了？",
    "enabledTools": ["get_time"]
  }'
```

### 2.3 Text Process (文本处理)
```bash
curl -X POST http://localhost:8080/api/v1/agent/invoke \
  -H "Content-Type: application/json" \
  -d '{
    "userInput": "把 hello world 转成大写",
    "enabledTools": ["text_process"]
  }'
```

### 2.4 Random (随机数)
```bash
curl -X POST http://localhost:8080/api/v1/agent/invoke \
  -H "Content-Type: application/json" \
  -d '{
    "userInput": "生成一个 1-100 之间的随机数",
    "enabledTools": ["random"]
  }'
```

### 2.5 Get User Info (用户信息)
```bash
curl -X POST http://localhost:8080/api/v1/agent/invoke \
  -H "Content-Type: application/json" \
  -d '{
    "userInput": "获取当前用户信息",
    "enabledTools": ["get_user_info"]
  }'
```

---

## 3. 记忆系统 API

### 3.1 保存记忆
```bash
curl -X POST http://localhost:8080/api/v1/memory/save \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user-001",
    "type": "EPISODIC",
    "content": "用户询问了天气相关问题",
    "sessionId": "session-001"
  }'
```

### 3.2 检索记忆
```bash
curl -X GET "http://localhost:8080/api/v1/memory/retrieve?userId=user-001&type=EPISODIC&query=天气"
```

### 3.3 设置用户偏好
```bash
curl -X POST http://localhost:8080/api/v1/memory/preference/user-001 \
  -H "Content-Type: application/json" \
  -d '{
    "key": "language",
    "value": "中文"
  }'
```

### 3.4 获取用户偏好
```bash
curl -X GET http://localhost:8080/api/v1/memory/preference/user-001/language
```

### 3.5 获取记忆统计
```bash
curl -X GET http://localhost:8080/api/v1/memory/stats/user-001
```

### 3.6 清除用户记忆
```bash
curl -X DELETE http://localhost:8080/api/v1/memory/clear/user-001
```

---

## 4. RAG 检索 API

### 4.1 添加文档到知识库
```bash
curl -X POST http://localhost:8080/api/v1/rag/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "collection": "knowledge-base",
    "documents": [
      {"content": "AI Agent 是什么？", "metadata": {"source": "docs"}},
      {"content": "机器学习是人工智能的分支", "metadata": {"source": "docs"}}
    ]
  }'
```

### 4.2 检索知识库
```bash
curl -X POST http://localhost:8080/api/v1/rag/query \
  -H "Content-Type: application/json" \
  -d '{
    "collection": "knowledge-base",
    "query": "什么是 AI Agent？",
    "topK": 5
  }'
```

### 4.3 启用 RAG 的 Agent 调用
```bash
curl -X POST http://localhost:8080/api/v1/agent/invoke \
  -H "Content-Type: application/json" \
  -d '{
    "userInput": "AI Agent 是什么？",
    "enableRag": true,
    "ragCollection": "knowledge-base"
  }'
```

---

## 5. 集群管理 API

### 5.1 获取服务实例列表
```bash
curl -X GET http://localhost:8080/api/v1/cluster/instances/agent-service
```

### 5.2 获取服务信息
```bash
curl -X GET http://localhost:8080/api/v1/cluster/service/agent-service
```

### 5.3 健康检查
```bash
curl -X GET http://localhost:8080/api/v1/agent/health
```

---

## 6. 监控指标

### 6.1 查看所有指标
```bash
curl http://localhost:8080/actuator/metrics | python3 -m json.tool
```

### 6.2 查看特定指标
```bash
curl http://localhost:8080/actuator/metrics/agent_requests_total
curl http://localhost:8080/actuator/metrics/agent_llm_call_latency
curl http://localhost:8080/actuator/metrics/agent_tool_calls_total
```

### 6.3 Prometheus 格式指标
```bash
curl http://localhost:8080/actuator/prometheus
```

---

## 7. 请求参数说明

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| userInput | String | 是 | 用户输入/问题 |
| sessionId | String | 否 | 会话 ID，用于关联多轮对话 |
| enabledTools | Array | 否 | 启用的工具列表 |
| enableRag | Boolean | 否 | 是否启用 RAG 检索 |
| ragCollection | String | 否 | RAG 知识库 collection 名称 |
| llmProvider | String | 否 | LLM 提供者 (openai/anthropic/azure/local) |
| systemPrompt | String | 否 | 系统提示词覆盖 |
| maxIterations | Integer | 否 | 最大迭代次数 |

---

## 8. 响应格式

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "responseId": "resp-uuid",
    "sessionId": "session-001",
    "content": "回复内容...",
    "completed": true,
    "status": "COMPLETED",
    "llmProvider": "openai",
    "tokenUsage": {
      "promptTokens": 100,
      "completionTokens": 50,
      "totalTokens": 150
    },
    "toolCalls": [
      {
        "toolName": "calculator",
        "input": "123 + 456",
        "output": "579",
        "durationMs": 10,
        "status": "SUCCESS"
      }
    ],
    "latencyMs": 1234
  },
  "timestamp": 1774666368325
}
```

---

## 9. 错误码

| 错误码 | 说明 |
|--------|------|
| 200 | 成功 |
| 400 | 请求参数错误 |
| 401 | API Key 无效 |
| 429 | 请求频率超限 |
| 500 | 服务器内部错误 |

---

## 10. 启动命令

```bash
# 启动 PGVector (如果使用向量存储)
docker run -d --name ai-agent-pgvector \
  -p 5432:5432 \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=postgres \
  ankane/pgvector:v0.5.1

# 启动 Java 应用
java -jar agent-boot-1.0.0-SNAPSHOT.jar
```
