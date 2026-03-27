# AI Agent 能力平台

企业级 AI Agent 能力平台，支持 2000+ QPS 高并发，采用 Java + Python 混合架构。参考 OpenClaw 设计理念。

## 架构概览

```
┌─────────────────────────────────────────────────────────────────┐
│                        API Gateway (Java)                        │
│                    限流 / 鉴权 / 路由 / 监控                      │
└─────────────────────────────────────────────────────────────────┘
                                │
        ┌───────────────────────┼───────────────────────┐
        ↓                       ↓                       ↓
┌───────────────┐       ┌───────────────┐       ┌───────────────┐
│  Agent Core   │       │  Agent Core   │       │  Agent Core   │
│  (Java/Boot)  │       │  (Java/Boot)  │       │  (Java/Boot)  │
│   Node 1      │       │   Node 2      │       │   Node N      │
│  + Nacos      │       │  + Nacos      │       │  + Nacos      │
└───────────────┘       └───────────────┘       └───────────────┘
        │                       │                       │
        └───────────────────────┼───────────────────────┘
                                ↓
        ┌───────────────────────┼───────────────────────┐
        ↓                       ↓                       ↓
  ┌───────────┐          ┌───────────┐          ┌───────────┐
  │  Python   │          │  Python   │          │  Python   │
  │  Worker   │          │  Worker   │          │  Worker   │
  │ (Tool/RAG)│          │ (Tool/RAG)│          │ (Tool/RAG)│
  └───────────┘          └───────────┘          └───────────┘
```

## 核心模块

### 1. Agent Core (Java Spring Boot)
- **高并发 WebClient**: 连接池优化 (maxConnections: 1000)
- **LLM Router**: 模型选择 (OpenAI/Claude/Azure/本地)、流量分配、降级策略
- **ReAct Engine**: 多轮推理循环 (Thought → Action → Observation)
- **Tool Router**: 同步/异步 Tool 调用、超时控制、最大并发限制
- **RateLimiter**: Redis Token Bucket 多维度限流 (RPM/TPM)
- **LLMCache**: Redis SHA-256 响应缓存，减少重复调用
- **Nacos 集群**: 服务注册发现、健康检查、实例负载均衡

### 2. Python Worker
- **Tool Executor**: FastAPI 异步任务执行
- **RAG Engine**: Milvus/pgvector 向量检索、召回排序
- **Embedding 服务**: OpenAI text-embedding-3-small + 本地 sentence-transformers
- **Rerank 模块**: BGE、Cohere、Cross-Encoder 支持
- **Python Tools**: Web Search (DuckDuckGo) 等预置工具

### 3. 多级记忆系统
- **情景记忆 (EPISODIC)**: Milvus 向量存储，对话历史
- **事实记忆 (FACTUAL)**: Milvus 向量存储，实体知识 (entityKey 唯一，自动覆盖)
- **偏好记忆 (PREFERENCE)**: Redis Hash 快速访问 + Milvus 长期备份

### 4. 向量数据库
- **Milvus**: 默认向量存储，连接池管理
- **pgvector**: PostgreSQL 向量扩展支持
- **向量维度**: 1536 维 (OpenAI text-embedding-3-small)

## 技术栈

| 层级 | 技术 |
|------|------|
| API Gateway | Spring Cloud Gateway |
| 服务注册 | Alibaba Nacos |
| 消息队列 | Apache Kafka |
| 缓存/限流 | Redis Cluster |
| 向量存储 | Milvus / pgvector |
| LLM | OpenAI GPT-4o / Claude / Azure OpenAI / 本地 Ollama |
| Embedding | OpenAI / sentence-transformers |
| Python 框架 | FastAPI / Celery |

## 快速开始

### 前置条件
- JDK 17+
- Maven 3.8+
- Python 3.11+
- Docker & Docker Compose

### 外部依赖
| 服务 | 端口 | 用途 |
|------|------|------|
| Redis | 6379 | 短期记忆、LLM缓存、限流 |
| Kafka | 9092 | Java-Python 通信 |
| Milvus | 19530 | 向量数据库/RAG |
| Nacos | 8848 | 服务发现/配置中心 |
| Prometheus | 9090 | 监控 |
| Grafana | 3000 | 可视化 |

### 构建

```bash
# 构建 Java 模块
cd agent-core
mvn clean package -DskipTests

# 构建 Python Worker
cd ../python-worker
pip install -r requirements.txt
```

### 运行

```bash
# 启动基础设施 (使用 docker-compose)
cd infra
docker-compose up -d

# 启动 Java Agent
java -jar agent-core/agent-boot/target/agent-boot-1.0.0-SNAPSHOT.jar

# 启动 Python Worker
cd python-worker
python main.py
```

## 项目结构

```
ai-agent-platform/
├── pom.xml                              # Maven 父 pom
├── README.md
├── agent-core/                          # Java 核心模块
│   ├── agent-api/                       # API 层 (Controller + Filter)
│   ├── agent-service/                   # 业务逻辑层
│   │   ├── prompt/                      # Prompt 构造器
│   │   ├── llm/                        # LLM 路由
│   │   ├── react/                      # ReAct 引擎
│   │   ├── tools/                      # Tool 调度
│   │   ├── memory/                     # 多级记忆系统
│   │   ├── vector/                     # 向量存储工厂
│   │   ├── embedding/                  # Embedding 服务
│   │   ├── cache/                      # LLM 响应缓存
│   │   └── ratelimit/                  # 限流器
│   ├── agent-common/                   # 公共组件
│   └── agent-boot/                     # Spring Boot 启动
├── python-worker/                       # Python Worker 模块
│   ├── tools/                           # Python Tools
│   ├── rag/                            # RAG 引擎
│   ├── routers/                        # FastAPI 路由
│   │   ├── agent.py                    # Agent 执行路由
│   │   ├── embedding.py                # Embedding 路由
│   │   └── tools.py                    # Tool 调用路由
│   └── main.py                         # FastAPI 入口
├── infra/                               # 基础设施配置
│   ├── docker-compose.yml              # Docker Compose
│   └── mysql/                          # MySQL 配置
├── scripts/                             # 部署脚本
└── docs/                               # 文档
```

## API 文档

启动服务后访问:
- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI: http://localhost:8080/v3/api-docs

### 核心接口

#### POST /api/v1/agent/invoke
同步执行 Agent

```json
{
  "sessionId": "session-123",
  "userId": "user-456",
  "userInput": "帮我查询今天的天气",
  "agentType": "assistant",
  "enableRag": true
}
```

#### POST /api/v1/memory/save
保存记忆

```json
{
  "type": "EPISODIC",
  "userId": "user123",
  "sessionId": "session456",
  "content": "用户说想减肥",
  "importance": 0.8
}
```

#### GET /api/v1/memory/retrieve
检索记忆

```
GET /api/v1/memory/retrieve?userId=user123&query=用户想做什么&type=EPISODIC
```

## 配置说明

主要配置项 (application.yml):

```yaml
aiagent:
  llm:
    default-provider: openai
    providers:
      openai:
        api-key: ${OPENAI_API_KEY}
        base-url: ${OPENAI_BASE_URL}
        model: gpt-4o
  tool:
    async-enabled: true
    timeout: 30
    max-concurrent: 50
  ratelimit:
    enabled: true
    rpm:
      max-requests: 100
    tpm:
      max-tokens: 50000
  memory:
    collections:
      episodic: episodic_memory
      factual: factual_memory
  vector-store:
    provider: milvus  # milvus | pgvector
```

## 性能优化

- **WebClient 连接池**: maxConnections=1000, perRoute=100
- **LLM 缓存**: Redis SHA-256 缓存，相同 prompt 避免重复调用
- **限流**: Redis Token Bucket，支持 RPM/TPM 多维度限流
- **异步 Tool**: 超时控制，最大并发限制
- **Nacos 集群**: 多实例部署，负载均衡

## 监控

- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000
- actuator: http://localhost:8080/actuator
- 健康检查: http://localhost:8080/actuator/health

## Roadmap

| 模块 | 状态 | 说明 |
|------|------|------|
| 多级记忆系统 | ✅ | EPISODIC/FACTUAL/PREFERENCE |
| RAG 集成 | ✅ | Milvus + pgvector |
| Rerank | ✅ | BGE/Cohere/Cross-Encoder |
| Embedding 集成 | ✅ | OpenAI + 本地模型 |
| Agent 集群 | ✅ | Nacos 服务注册发现 |
| LLM 缓存 | ✅ | Redis SHA-256 |
| 限流器 | ✅ | Redis Token Bucket |
| 插件系统 | 🚧 | 规划中 |
| 消息通道 | 🚧 | 规划中 |
| 安全机制 | 🚧 | 规划中 |
| 定时任务 | 🚧 | 规划中 |
| MCP 支持 | 🚧 | 规划中 |

## License

MIT
