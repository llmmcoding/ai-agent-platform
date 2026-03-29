# AI Agent 能力平台

企业级 AI Agent 能力平台，支持 2000+ QPS 高并发，采用 Java + Python 混合架构。参考 OpenClaw 设计理念，完整实现 Agent 全生命周期管理。

## 核心特性

- **🚀 高性能**: WebClient 连接池优化，支持 2000+ QPS
- **🧠 智能压缩**: 三层上下文压缩（Micro/Auto/Manual），无限会话支持
- **📊 全链路监控**: Prometheus + Grafana + OpenTelemetry 链路追踪
- **🔒 企业级安全**: API Key 认证、多租户隔离、路径沙箱保护
- **🎯 完整 RAG**: 4级降级策略、并行查询、混合搜索
- **🔌 插件化架构**: Hook 全生命周期（25个Hook点）、插件系统、浏览器自动化

## 架构概览

```
┌─────────────────────────────────────────────────────────────────┐
│                        API Gateway (Java)                        │
│              限流 / 鉴权 / 路由 / 监控 / 链路追踪                  │
└─────────────────────────────────────────────────────────────────┘
                                │
        ┌───────────────────────┼───────────────────────┐
        ↓                       ↓                       ↓
┌───────────────┐       ┌───────────────┐       ┌───────────────┐
│  Agent Core   │       │  Agent Core   │       │  Agent Core   │
│  (Java/Boot)  │◄──────│  (Java/Boot)  │◄──────│  (Java/Boot)  │
│   Node 1      │ Nacos │   Node 2      │ Nacos │   Node N      │
│  + Hooks      │       │  + Hooks      │       │  + Hooks      │
│  + Tasks      │       │  + Tasks      │       │  + Tasks      │
└───────────────┘       └───────────────┘       └───────────────┘
        │                       │                       │
        └───────────────────────┼───────────────────────┘
                                ↓
        ┌───────────────────────┼───────────────────────┐
        ↓                       ↓                       ↓
  ┌───────────┐          ┌───────────┐          ┌───────────┐
  │  Redis    │          │  Milvus   │          │  Grafana  │
  │  (缓存)    │          │ (向量库)   │          │ (监控)    │
  └───────────┘          └───────────┘          └───────────┘
```

## 核心模块

### 1. Agent Core (Java Spring Boot)

#### 推理引擎
- **ReAct Engine**: 多轮推理循环 (Thought → Action → Observation)
- **流式响应**: SSE / NDJSON / 纯文本真流式
- **LLM Router**: 模型选择 (OpenAI/Claude/Azure/本地)、流量分配、降级策略

#### 扩展系统
- **Hook 全生命周期**: 25 个 Hook 点（Gateway/Session/Agent/LLM/Tool/Subagent/Memory/Task/RAG）
- **插件系统**: 内置 WebSearchPlugin、BrowserPlugin 等
- **任务系统**: DAG 依赖图、文件持久化、自动解阻塞

#### 性能优化
- **WebClient 连接池**: maxConnections=1000
- **RateLimiter**: Redis Token Bucket 多维度限流 (RPM/TPM)
- **LLMCache**: Redis SHA-256 响应缓存
- **并行 RAG**: 多路召回并行查询

#### 记忆系统
- **三层上下文压缩**: Micro Compact → Auto Compact → Manual Compact
- **多级记忆**: 短期记忆 (Redis) + 长期记忆 (Milvus)
- **记忆摘要**: 自动触发 LLM 总结

### 2. RAG 系统

- **4级降级**: 向量搜索 → 关键词搜索 → LLM摘要 → 无RAG
- **并行查询**: 多路召回合并排序
- **混合搜索**: 向量相似度 + BM25 全文检索
- **文档切片**: 递归字符/固定大小/语义切片策略
- **知识库版本**: 版本管理与灰度发布

### 3. 浏览器自动化

- **Selenium 集成**: Chrome/Edge 浏览器控制
- **BrowserPlugin**: 插件化浏览器工具
- **会话管理**: 多用户浏览器隔离

### 4. 监控与可观测性

- **Prometheus 指标**: QPS/延迟/错误率/Token消耗
- **Grafana Dashboard**: 可视化监控大盘
- **OpenTelemetry 链路追踪**: 分布式链路追踪
- **告警系统**: 配额告警、错误率告警、延迟告警

### 5. Python Worker (可选)

- **Embedding 服务**: OpenAI + sentence-transformers
- **RAG 索引**: Milvus 向量写入
- **工具执行**: Web Search、文件操作等

## 技术栈

| 层级 | 技术 |
|------|------|
| API Gateway | Spring Cloud Gateway |
| 服务框架 | Spring Boot 3.2 + WebFlux |
| 服务注册 | Alibaba Nacos |
| 缓存/限流 | Redis Cluster |
| 向量存储 | Milvus / pgvector |
| 链路追踪 | OpenTelemetry + Jaeger |
| 监控 | Prometheus + Grafana |
| LLM | OpenAI GPT-4o / Claude / Azure / Ollama |
| Embedding | OpenAI / Jina / 本地模型 |

## 快速开始

### 前置条件
- JDK 17+
- Maven 3.8+
- Docker & Docker Compose

### 外部依赖
| 服务 | 端口 | 用途 |
|------|------|------|
| Redis | 6379 | 短期记忆、LLM缓存、限流 |
| Milvus | 19530 | 向量数据库/RAG |
| Nacos | 8848 | 服务发现/配置中心 |
| Prometheus | 9090 | 监控 |
| Grafana | 3000 | 可视化 |
| Jaeger | 16686 | 链路追踪 |

### 构建与运行

```bash
# 1. 克隆仓库
git clone https://github.com/llmmcoding/ai-agent-platform.git
cd ai-agent-platform

# 2. 启动基础设施
cd infra
docker-compose up -d

# 3. 构建 Java 模块
cd ../agent-core
mvn clean package -DskipTests

# 4. 启动 Agent 服务
java -jar agent-boot/target/agent-boot-1.0.0-SNAPSHOT.jar
```

## 项目结构

```
ai-agent-platform/
├── agent-core/                          # Java 核心模块
│   ├── agent-api/                       # API 层
│   ├── agent-service/                   # 业务逻辑层
│   │   ├── hook/                        # Hook 系统 (25个Hook点)
│   │   ├── task/                        # 任务系统 (DAG)
│   │   ├── memory/                      # 记忆系统 + 上下文压缩
│   │   ├── rag/                         # RAG 引擎
│   │   ├── browser/                     # 浏览器自动化
│   │   ├── streaming/                   # 流式响应
│   │   ├── tracing/                     # 链路追踪
│   │   ├── cluster/                     # Nacos 集群
│   │   └── metrics/                     # Prometheus 指标
│   ├── agent-common/                    # 公共组件
│   └── agent-boot/                      # Spring Boot 启动
├── python-worker/                       # Python Worker (可选)
├── infra/                               # 基础设施配置
│   ├── docker-compose.yml               # Docker Compose
│   ├── grafana/                         # Grafana Dashboard
│   └── k8s/                             # K8s 部署配置
├── docs/                                # 文档
└── scripts/                             # 部署脚本
```

## API 文档

启动服务后访问:
- Swagger UI: http://localhost:8080/swagger-ui.html
- Actuator: http://localhost:8080/actuator
- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000

### 核心接口示例

#### Agent 执行（同步）
```bash
POST /api/v1/agent/invoke
Content-Type: application/json

{
  "sessionId": "session-123",
  "userId": "user-456",
  "query": "帮我查询今天的天气",
  "enableRag": true
}
```

#### Agent 执行（流式）
```bash
POST /api/v1/agent/stream
Accept: text/event-stream

{
  "sessionId": "session-123",
  "query": "写一段代码",
  "stream": true
}
```

#### 创建任务
```bash
POST /api/v1/tasks

{
  "sessionId": "session-123",
  "subject": "实现用户登录功能",
  "description": "使用 JWT 实现登录",
  "blockedBy": ["task-001"]
}
```

#### 手动压缩上下文
```bash
POST /api/v1/agent/{sessionId}/compact
```

## 配置说明

主要配置项 (`application.yml`):

```yaml
aiagent:
  llm:
    default-provider: openai
    providers:
      openai:
        api-key: ${OPENAI_API_KEY}
        model: gpt-4o

  memory:
    compaction:
      enabled: true
      token-threshold: 8000
      preserve-recent: 3
    short-term:
      ttl: 3600
      max-size: 100

  rag:
    enabled: true
    parallel: true
    fallback-enabled: true
    vector-weight: 0.7
    keyword-weight: 0.3

  ratelimit:
    enabled: true
    rpm:
      max-requests: 100
    tpm:
      max-tokens: 50000

  tracing:
    enabled: true
    sampling-rate: 0.1
```

## 核心设计

### 三层上下文压缩

```
Layer 1: Micro Compact (每轮执行)
  [tool_result] → [Previous: used {tool_name}]

Layer 2: Auto Compact (Token > 8000)
  保存完整对话到 .transcripts/
  LLM 生成总结 → 替换历史消息

Layer 3: Manual Compact (模型调用)
  立即执行压缩
```

### Hook 生命周期

```
Gateway:   GATEWAY_START → GATEWAY_STOP
Session:   SESSION_START → SESSION_END
Agent:     BEFORE_AGENT_START → AGENT_END
LLM:       BEFORE_MODEL_RESOLVE → LLM_INPUT → LLM_OUTPUT
Tool:      BEFORE_TOOL_CALL → AFTER_TOOL_CALL
Memory:    BEFORE_COMPACTION → AFTER_COMPACTION
Task:      TASK_CREATED → TASK_COMPLETED
RAG:       BEFORE_RAG_QUERY → AFTER_RAG_QUERY
```

### 任务依赖图

```
task-001 (completed)
    ↓
task-002 (blocked) ──► 自动解阻塞 ──► (pending)
    ↓
task-003 (blocked)
```

## 性能指标

- **QPS**: 2000+ (单节点)
- **延迟**: P99 < 500ms (不含 LLM)
- **上下文压缩**: 减少 70%+ Token 消耗
- **RAG 召回率**: 90%+ (混合搜索)

## Roadmap

| 模块 | 状态 | 说明 |
|------|------|------|
| Hook 全生命周期 | ✅ | 25个Hook点 |
| 任务系统 | ✅ | DAG + 文件持久化 |
| 上下文压缩 | ✅ | 三层压缩 |
| RAG 系统 | ✅ | 4级降级 + 并行查询 |
| 流式响应 | ✅ | SSE/NDJSON |
| 浏览器自动化 | ✅ | Selenium |
| 监控告警 | ✅ | Prometheus + Grafana |
| 链路追踪 | ✅ | OpenTelemetry |
| 多Agent一致性 | ✅ | Saga + 幂等 |
| Prompt A/B测试 | ✅ | 实验框架 |
| 多租户 | ✅ | 租户隔离 |
| 限流器 | ✅ | Token Bucket |
| LLM缓存 | ✅ | Redis SHA-256 |

## 横向对比

| 特性 | ai-agent-platform | learn-claude-code | openclaw |
|------|-------------------|-------------------|----------|
| 上下文压缩 | 3层 | 3层 | 2层+质量保障 |
| Hook 系统 | 25个 | 无 | 20+个 |
| 任务系统 | DAG | DAG | 基础 |
| RAG | 4级降级 | 无 | Hybrid |
| 监控 | 完整 | 无 | 基础 |
| 浏览器 | ✅ | ❌ | ✅ |
| 流式 | ✅ | ❌ | ✅ |

详细对比报告: [docs/comparison-context-memory-compaction.md](docs/comparison-context-memory-compaction.md)

## 文档

- [用户指南](docs/AI-Agent-Platform-User-Guide.md)
- [架构对比](docs/comparison-context-memory-compaction.md)

## License

MIT
