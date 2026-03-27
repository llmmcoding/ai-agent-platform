# AI Agent 能力平台

企业级 AI Agent 能力平台，支持 2000+ QPS 高并发，采用 Java + Python 混合架构。

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
- **Prompt Builder**: 动态组装 System Prompt、Rules、Memory Context、Tools 描述
- **LLM Router**: 模型选择 (OpenAI/Claude/本地开源)、流量分配、降级策略
- **ReAct Engine**: 多轮推理循环 (Thought → Action → Observation)
- **Tool Router**: 同步/异步 Tool 调用、结果聚合、超时控制

### 2. Python Worker
- **Tool Executor**: FastAPI + Celery 异步任务队列
- **RAG Engine**: Milvus 向量检索、文本 chunking、召回排序
- **Python Tools**: Web Search、Calculator、Code Interpreter 等预置工具

### 3. Memory Layer
- **短期记忆**: Redis Cluster (Session Context)
- **长期记忆**: Milvus 向量库 + MySQL 索引表

## 快速开始

### 前置条件
- JDK 17+
- Maven 3.8+
- Python 3.11+
- Docker & Docker Compose (可选)

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
# 启动 Java Agent (需要先启动 Nacos、Kafka、Redis)
java -jar agent-core/agent-boot/target/agent-boot-1.0.0-SNAPSHOT.jar

# 启动 Python Worker
cd python-worker
python main.py
```

### Docker Compose (开发环境)

```bash
docker-compose -f infra/docker-compose.yml up -d
```

## 项目结构

```
ai-agent-platform/
├── pom.xml                          # Maven 父 pom
├── agent-core/                      # Java 核心模块
│   ├── agent-api/                   # API 层 (Controller + Filter)
│   ├── agent-service/               # 业务逻辑层
│   │   ├── prompt/                   # Prompt 构造器
│   │   ├── llm/                      # LLM 路由
│   │   ├── react/                    # ReAct 引擎
│   │   └── tools/                   # Tool 调度
│   ├── agent-common/                 # 公共组件
│   └── agent-boot/                  # Spring Boot 启动
├── python-worker/                   # Python Worker 模块
│   ├── tools/                       # Python Tools
│   ├── rag/                         # RAG 引擎
│   └── main.py                      # FastAPI 入口
├── infra/                           # 基础设施配置
│   ├── k8s/                         # Kubernetes 部署
│   └── docker-compose.yml           # Docker Compose
├── scripts/                         # 部署脚本
└── docs/                            # 文档
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

#### POST /api/v1/agent/invoke/async
异步执行 Agent

#### GET /api/v1/agent/task/{taskId}
查询异步任务状态

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
  memory:
    short-term:
      ttl: 3600
```

## 部署

### Kubernetes

```bash
./scripts/deploy-k8s.sh
```

### HPA 配置

平台已配置自动扩缩容:
- 最小副本: 3
- 最大副本: 20
- CPU 目标利用率: 70%
- Memory 目标利用率: 80%

## 监控

- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000
- actuator: http://localhost:8080/actuator

## License

MIT
