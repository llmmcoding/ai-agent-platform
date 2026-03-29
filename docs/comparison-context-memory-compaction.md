# 上下文管理、记忆管理、上下文压缩 - 三系统详细对比报告

> **评估日期**: 2026-03-29
> **评估系统**:
> - ai-agent-platform (当前项目)
> - learn-claude-code
> - openclaw

---

## 一、上下文管理 (Context Management)

### 1.1 能力对比矩阵

| 维度 | ai-agent-platform | learn-claude-code | openclaw |
|------|-------------------|-------------------|----------|
| **核心哲学** | 企业级服务化 | 单机文件化 | 网关插件化 |
| **Context 存储** | Redis ZSET + 内存 | 内存列表 + 文件 | 会话 JSONL 文件 |
| **Context 构建** | PromptBuilder 动态组装 | 简单消息列表 | ContextEngine 插件化 |
| **多会话支持** | 完整 (SessionID) | 单会话 | 完整 (SessionID) |
| **工具结果处理** | 保留完整 | Layer 1 微压缩 | Pruning + 压缩 |
| **系统提示管理** | 静态配置 | 无 | 动态构建 (Skills/Tools) |
| **子Agent隔离** | 支持 (新上下文) | 支持 (全新列表) | 支持 (Context Share) |

### 1.2 各系统实现详解

#### ai-agent-platform

```java
// PromptBuilderImpl.java - 动态组装上下文
public String buildWithMemoryAndRag(AgentRequest request) {
    StringBuilder context = new StringBuilder();

    // 1. 系统提示
    context.append("You are a helpful AI assistant...\n");

    // 2. RAG 结果 (如启用)
    if (request.isEnableRag()) {
        context.append(ragService.search(query));
    }

    // 3. 长期记忆
    context.append(memoryService.getLongTermMemory(userId, query));

    // 4. 短期记忆 (Redis ZSET 按时间排序)
    context.append(memoryService.getShortTermMemory(sessionId));

    // 5. 当前请求
    context.append("User: ").append(request.getQuery());

    return context.toString();
}
```

**设计特点**:
- **分层架构**: 系统提示 -> RAG -> 长期记忆 -> 短期记忆 -> 当前输入
- **Redis ZSET**: Score 为时间戳，支持高效范围查询和裁剪
- **动态组装**: 每次请求重新构建完整上下文
- **无状态服务**: 适合水平扩展

**优点**:
- 企业级可扩展性，支持多实例共享状态
- Redis 持久化保证数据不丢失
- 清晰的关注点分离

**缺点**:
- 每次请求都重新组装，开销较大
- Context 没有版本管理
- 工具结果全部保留，容易膨胀

---

#### learn-claude-code

```python
# s06_context_compact.py - 简单消息列表
messages = []  # 纯内存列表

def agent_loop(messages: list):
    while True:
        # Layer 1: 微压缩 (每轮)
        micro_compact(messages)

        # 检查阈值
        if estimate_tokens(messages) > THRESHOLD:
            messages[:] = auto_compact(messages)  # Layer 2

        response = client.messages.create(
            model=MODEL,
            system=SYSTEM,  # 固定系统提示
            messages=messages,
            tools=TOOLS
        )
```

**设计特点**:
- **极简模型**: 纯内存消息列表
- **三层压缩**: micro -> auto -> manual
- **文件持久化**: 压缩前保存完整对话到 `.transcripts/`
- **工具即函数**: 简单直接的工具调用

**优点**:
- 代码简洁，易于理解和调试
- 文件化存储，无需外部依赖
- 无限会话支持 (通过压缩)

**缺点**:
- 单机会话，不支持分布式
- 无 RAG 集成
- 系统提示固定，无法动态调整

---

#### openclaw

```typescript
// context.ts - ContextEngine 插件接口
interface ContextEngine {
  bootstrap(): Promise<void>;
  ingest(event: ContextEvent): Promise<void>;
  assemble(params: AssembleParams): Promise<AgentMessage[]>;
  compact(): Promise<CompactionResult>;
  afterTurn(): Promise<void>;
  maintain(): Promise<void>;
}

// 内置 legacy engine + 可插拔引擎
```

**设计特点**:
- **ContextEngine 插件**: 可替换的上下文管理策略
- **Project Context**: 自动注入工作区文件 (AGENTS.md, SOUL.md 等)
- **Skills 按需加载**: 系统提示只列技能名，需要时 `read` 完整内容
- **Tool 双成本**: 列表文本 + JSON Schema 都计入 Token

**优点**:
- 插件化架构，高度可扩展
- 精细的 Context 控制 (`/context` 命令查看)
- Skills 延迟加载减少系统提示大小

**缺点**:
- 架构复杂，学习成本高
- 默认 legacy engine 功能有限
- 插件引擎需要正确实现 `ownsCompaction`

---

### 1.3 对比总结

| 评估维度 | ai-agent-platform | learn-claude-code | openclaw | 推荐 |
|----------|-------------------|-------------------|----------|------|
| 架构复杂度 | 中 (Spring Boot) | 低 (Python 脚本) | 高 (插件系统) | learn-claude-code |
| 可扩展性 | 高 (微服务) | 低 (单机) | 高 (插件) | ai-agent-platform |
| Context 可见性 | 中 (需查 Redis) | 高 (内存直接看) | 高 (`/context` 命令) | openclaw |
| 工具结果管理 | 保留全部 | 微压缩 | Pruning + 压缩 | openclaw |
| 系统提示动态性 | 中 | 低 | 高 | openclaw |

---

## 二、记忆管理 (Memory Management)

### 2.1 能力对比矩阵

| 维度 | ai-agent-platform | learn-claude-code | openclaw |
|------|-------------------|-------------------|----------|
| **短期记忆** | Redis ZSET (TTL) | 内存消息列表 | 会话 JSONL 文件 |
| **长期记忆** | Milvus 向量库 | 文件摘要 (`.transcripts/`) | SQLite + 向量扩展 |
| **记忆类型** | 结构化对话 | 原始对话文件 | 代码片段 + 文档 |
| **RAG 支持** | 完整 (4级降级) | 无 | 混合搜索 (向量+FTS) |
| **记忆写入** | Kafka 异步 | 同步文件写入 | 自动同步 (文件监听) |
| **记忆检索** | 向量相似度 | 无 | Hybrid (向量+关键词+MMR) |
| **记忆压缩** | 摘要触发 | 三层压缩 | Compaction 总结 |

### 2.2 各系统实现详解

#### ai-agent-platform

```java
// MemoryServiceImpl.java - 两级记忆
@Service
public class MemoryServiceImpl implements MemoryService {

    // 短期: Redis ZSET
    private static final String SHORT_TERM_KEY_PREFIX = "ai:memory:session:";

    // 长期: Milvus 向量库 (通过 Python Worker)

    public String getShortTermMemory(String sessionId) {
        // ZREVRANGE 获取最近消息
        Set<String> entries = redisTemplate.opsForZSet()
            .reverseRange(key, 0, -1);
        return buildContextFromEntries(entries);
    }

    public String getLongTermMemory(String userId, String query) {
        // 调用 Python Worker RAG API
        Map<String, Object> request = Map.of(
            "query", query,
            "collection", longTermCollection,
            "top_k", 5
        );
        // WebClient 调用 /api/v1/rag/query
    }

    public void saveLongTermMemory(String userId, String content) {
        // Kafka 异步发送
        toolKafkaClient.sendToolRequest("rag_index_memory", input, userId);
    }
}
```

**设计特点**:
- **分层存储**: Redis (热) -> Milvus (温)
- **异步写入**: Kafka 解耦，避免阻塞主流程
- **4级 RAG 降级**: 向量 -> 关键词 -> LLM摘要 -> 无RAG
- **TTL 管理**: Redis ZSET 自动过期

**优点**:
- 企业级高可用
- 向量检索精准
- 异步写入不阻塞

**缺点**:
- 依赖外部组件多 (Redis, Kafka, Milvus)
- 长期记忆检索需网络调用
- 没有本地文件备份

---

#### learn-claude-code

```python
# s06_context_compact.py - 文件化记忆
TRANSCRIPT_DIR = WORKDIR / ".transcripts"

def auto_compact(messages: list) -> list:
    # 保存完整对话到磁盘
    TRANSCRIPT_DIR.mkdir(exist_ok=True)
    transcript_path = TRANSCRIPT_DIR / f"transcript_{int(time.time())}.jsonl"
    with open(transcript_path, "w") as f:
        for msg in messages:
            f.write(json.dumps(msg, default=str) + "\n")

    # 请求 LLM 总结
    summary = client.messages.create(...)

    # 替换为总结
    return [
        {"role": "user", "content": f"[Conversation compressed...]\n\n{summary}"},
        {"role": "assistant", "content": "Understood. I have the context..."}
    ]
```

**设计特点**:
- **文件即记忆**: `.transcripts/` 目录保存完整历史
- **总结即压缩**: LLM 生成摘要替代原始消息
- **无向量检索**: 纯文件存储，无语义搜索

**优点**:
- 极简设计，零外部依赖
- 完整历史可审计
- 压缩后 Token 消耗低

**缺点**:
- 无法语义检索历史
- 文件 IO 可能成为瓶颈
- 多用户场景需自己扩展

---

#### openclaw

```typescript
// manager.ts - MemoryIndexManager
export class MemoryIndexManager implements MemorySearchManager {
    private db: DatabaseSync;  // SQLite
    private provider: EmbeddingProvider;

    async search(query: string, opts?: SearchOptions): Promise<MemorySearchResult[]> {
        // Hybrid 搜索策略
        const keywordResults = await this.searchKeyword(query, candidates);
        const vectorResults = await this.searchVector(queryVec, candidates);

        // MMR (Maximal Marginal Relevance) 去重
        // Temporal Decay 时间衰减
        return mergeHybridResults({
            vector: vectorResults,
            keyword: keywordResults,
            vectorWeight: hybrid.vectorWeight,
            textWeight: hybrid.textWeight,
            mmr: hybrid.mmr,
            temporalDecay: hybrid.temporalDecay
        });
    }

    async sync(params?: SyncParams): Promise<void> {
        // 文件监听自动同步
        // 支持批量处理 (Batching)
        // 失败恢复机制
    }
}
```

**设计特点**:
- **SQLite 本地存储**: 无需外部向量库
- **Hybrid 搜索**: 向量 + BM25 全文检索
- **MMR 去重**: 结果多样性保证
- **时间衰减**: 近期内容权重更高
- **自动同步**: 文件监听实时索引

**优点**:
- 单机性能极致
- Hybrid 检索召回率高
- 智能排序 (MMR + 时间)

**缺点**:
- SQLite 扩展依赖
- 大规模数据性能待验证
- 配置复杂

---

### 2.3 对比总结

| 评估维度 | ai-agent-platform | learn-claude-code | openclaw | 推荐 |
|----------|-------------------|-------------------|----------|------|
| 检索精度 | 高 (纯向量) | 无 | 极高 (Hybrid+MMR) | openclaw |
| 部署复杂度 | 高 (3个组件) | 极低 (文件) | 低 (SQLite) | learn-claude-code |
| 多用户支持 | 优秀 | 需扩展 | 良好 | ai-agent-platform |
| 离线可用 | 否 | 是 | 是 | openclaw/learn |
| 检索速度 | 中 (网络) | - | 快 (本地) | openclaw |

---

## 三、上下文压缩 (Context Compaction)

### 3.1 能力对比矩阵

| 维度 | ai-agent-platform | learn-claude-code | openclaw |
|------|-------------------|-------------------|----------|
| **压缩层级** | 2层 (Micro + Auto/Manual) | 3层 (Micro + Auto + Manual) | 2层 (Pruning + Compaction) |
| **触发条件** | Token 阈值 / 手动调用 | 每轮检查 / 阈值 / 手动 | 窗口溢出前 / 手动 `/compact` |
| **压缩算法** | 占位符替换 + LLM总结 | 占位符替换 + LLM总结 | 分块总结 + 渐进式合并 |
| **历史保留** | `.transcripts/` + `.summaries/` | `.transcripts/` | 会话 JSONL (内置) |
| **工具结果处理** | 保留最近3条 | 保留最近3条 | Strip details + 安全策略 |
| **标识符保护** | 无 | 无 | 严格 (UUID/URL/文件名) |
| **压缩质量审计** | 无 | 无 | 质量检查 + Fallback |

### 3.2 各系统实现详解

#### ai-agent-platform (已借鉴 learn-claude-code)

```java
// ContextCompactionService.java
@Service
public class ContextCompactionService {

    // Layer 1: Micro Compact - 每轮执行
    public List<ConversationMessage> microCompact(List<ConversationMessage> messages) {
        for (int i = 0; i < total - preserveRecent; i++) {
            if ("tool_result".equals(msg.getType())) {
                compacted.add(ConversationMessage.builder()
                    .content(String.format(TOOL_RESULT_MARKER, msg.getToolName()))
                    .build());
            }
        }
    }

    // Layer 2: Auto Compact - Token 阈值触发
    public CompactionResult autoCompact(String sessionId, List<ConversationMessage> messages) {
        int estimatedTokens = estimateTokens(messages);  // chars / 4
        if (estimatedTokens < tokenThreshold) {
            return CompactionResult.notNeeded(messages);
        }

        // 1. 保存完整对话
        String transcriptId = saveTranscript(sessionId, messages);

        // 2. LLM 生成总结
        String summary = generateSummary(messages);

        // 3. 替换为总结 + 保留最近3条
        return buildCompactedMessages(summary, messages);
    }
}
```

**设计特点**:
- **借鉴 learn-claude-code**: 三层压缩中的两层
- **文件持久化**: 压缩前保存完整对话
- **简单估算**: chars / 4 估算 Token

**优点**:
- 实现简单，已验证可用
- 保留最近消息保证连续性

**缺点**:
- Token 估算粗糙
- 无质量检查
- 无标识符保护

---

#### learn-claude-code (三层压缩原型)

```python
# s06_context_compact.py - 完整三层压缩

THRESHOLD = 50000
KEEP_RECENT = 3

# Layer 1: 每轮静默执行
def micro_compact(messages: list) -> list:
    """替换旧 tool_result 为占位符"""
    for msg in messages[:-3]:  # 保留最近3条
        if is_tool_result(msg) and len(content) > 100:
            msg["content"] = f"[Previous: used {tool_name}]"

# Layer 2: 自动触发
def auto_compact(messages: list) -> list:
    """Token 超阈值时触发"""
    if estimate_tokens(messages) <= THRESHOLD:
        return messages

    # 保存到 .transcripts/
    save_transcript(messages)

    # LLM 总结
    summary = llm.summarize(messages)

    # 替换为总结
    return [{"role": "user", "content": f"[Summary]: {summary}"}]

# Layer 3: 手动触发 (compact tool)
def manual_compact(focus: str) -> str:
    """模型或用户主动调用"""
    return auto_compact(messages)  # 同 Layer 2
```

**设计特点**:
- **渐进式压缩**: 微压缩 (每轮) -> 自动压缩 (阈值) -> 手动压缩 (按需)
- **智能占位**: `[Previous: used {tool_name}]` 保留语义
- **无限会话**: "The agent can forget strategically and keep working forever"

**优点**:
- 三层设计覆盖所有场景
- 微压缩几乎无开销
- 简单有效

**缺点**:
- Token 估算简单 (chars / 4)
- 无分块处理大消息
- 无质量回退机制

---

#### openclaw (生产级压缩)

```typescript
// compaction.ts - 生产级压缩
export async function summarizeInStages(params: {
  messages: AgentMessage[];
  contextWindow: number;
  parts?: number;  // 分块数
  maxChunkTokens: number;
  summarizationInstructions?: CompactionSummarizationInstructions;
}): Promise<string> {
    // 1. 自适应分块比例
    const ratio = computeAdaptiveChunkRatio(messages, contextWindow);

    // 2. 按 Token 份额分块
    const chunks = splitMessagesByTokenShare(messages, parts);

    // 3. 分块总结 (并行)
    const partialSummaries: string[] = [];
    for (const chunk of chunks) {
        partialSummaries.push(await summarizeWithFallback({...}));
    }

    // 4. 合并总结 (递归)
    return mergeSummaries(partialSummaries, params);
}

// 质量回退机制
export async function summarizeWithFallback(params) {
    // Try 1: 完整总结
    try {
        return await summarizeChunks(params);
    } catch (e) {
        // Try 2: 排除超大消息
        const smallMessages = messages.filter(m => !isOversized(m));
        return summarizeChunks({...params, messages: smallMessages});
    }
    // Try 3: 最终 Fallback
    return `Context contained ${messages.length} messages. Summary unavailable.`;
}

// 标识符保护
const IDENTIFIER_PRESERVATION_INSTRUCTIONS =
  "Preserve all opaque identifiers exactly as written: " +
  "UUIDs, hashes, IDs, tokens, API keys, hostnames, IPs, ports, URLs, file names.";
```

**设计特点**:
- **分阶段总结**: 大 Context 分块处理，避免超限
- **自适应比例**: 根据平均消息大小调整分块策略
- **质量回退**: 三级容错 (完整 -> 部分 -> 占位)
- **标识符保护**: 严格保留技术标识符
- **安全配置**: `identifierPolicy: "strict" | "off" | "custom"`

**优点**:
- 处理超大 Context 能力强
- 总结质量有保障
- 生产级容错

**缺点**:
- 实现复杂
- 多次 LLM 调用成本高
- 配置项多

---

### 3.3 压缩策略对比图

```
ai-agent-platform (两层):
┌─────────────────────────────────────────┐
│ Layer 1: Micro Compact (每轮)           │
│   [tool_result x1000] -> [Previous: used]│
│                                         │
│ Layer 2: Auto/Manual (阈值/手动)        │
│   Full Messages -> LLM Summary          │
│   + Save to .transcripts/               │
└─────────────────────────────────────────┘

learn-claude-code (三层):
┌─────────────────────────────────────────┐
│ Layer 1: Micro Compact (每轮静默)       │
│   [tool_result x1000] -> [Previous: used]│
│                                         │
│ Layer 2: Auto Compact (Token > 50000)   │
│   Full -> Save .transcripts/ -> Summary │
│                                         │
│ Layer 3: Manual Compact (/compact)      │
│   Immediate summarization               │
└─────────────────────────────────────────┘

openclaw (分层 + 质量保障):
┌─────────────────────────────────────────┐
│ Pruning (每轮内存级)                    │
│   Strip toolResult.details              │
│                                         │
│ Compaction (窗口溢出前)                 │
│   ├─ 自适应分块 (Adaptive Chunking)     │
│   ├─ 分块总结 (并行)                    │
│   ├─ 渐进合并 (Merge Summaries)         │
│   ├─ 标识符保护 (Identifier Policy)     │
│   └─ 质量回退 (Fallback)                │
│                                         │
│ Manual: /compact [custom instructions]  │
└─────────────────────────────────────────┘
```

---

### 3.4 对比总结

| 评估维度 | ai-agent-platform | learn-claude-code | openclaw | 推荐 |
|----------|-------------------|-------------------|----------|------|
| 压缩粒度 | 粗 (单层总结) | 细 (三层渐进) | 极细 (分块+合并) | openclaw |
| 大 Context 处理 | 可能超限 | 可能超限 | 分块处理 | openclaw |
| 总结质量 | 中 | 中 | 高 (质量检查) | openclaw |
| 开销控制 | 低 | 极低 | 中 (多次LLM) | learn-claude-code |
| 标识符保护 | 无 | 无 | 严格 | openclaw |
| 实现复杂度 | 低 | 极低 | 高 | ai-agent-platform |

---

## 四、综合评估与建议

### 4.1 各系统总体定位

| 系统 | 最佳场景 | 核心优势 | 主要短板 |
|------|----------|----------|----------|
| **ai-agent-platform** | 企业级多租户平台 | 高可用、RAG完整、监控完善 | 部署复杂、Context管理较简单 |
| **learn-claude-code** | 单机/个人开发 | 极简、可审计、无限会话 | 无分布式、无向量检索 |
| **openclaw** | 专业开发者工具 | 插件化、Context精细控制 | 学习曲线陡峭、配置复杂 |

### 4.2 ai-agent-platform 改进建议

基于对比分析，建议从以下方向继续优化：

#### 高优先级 (立即实施)

1. **引入 openclaw 的 Hybrid 搜索**
   - 在现有 Milvus 向量搜索基础上，增加 BM25 全文检索
   - 实现 MMR 去重和时间衰减排序
   - 提升 RAG 召回率

2. **增强 ContextCompaction 质量**
   - 借鉴 openclaw 的分块总结策略
   - 增加标识符保护配置
   - 添加质量回退机制

#### 中优先级 (后续规划)

3. **优化 Token 估算**
   - 从简单 `chars / 4` 升级为 tiktoken 精确计算
   - 增加 safety margin (openclaw 使用 1.2x)

4. **参考 learn-claude-code 的文件审计**
   - 增加 `.transcripts/` 结构化存储
   - 支持会话完整回放

### 4.3 最终评分

| 维度 | ai-agent-platform | learn-claude-code | openclaw |
|------|-------------------|-------------------|----------|
| Context 管理 | 7/10 | 7/10 | **9/10** |
| 记忆管理 | 8/10 | 5/10 | **9/10** |
| 上下文压缩 | 7/10 | 8/10 | **9/10** |
| **综合** | **7.3/10** | **6.7/10** | **9.0/10** |

### 4.4 结论

当前 ai-agent-platform 在**企业级特性** (监控、RAG降级、多Agent) 领先，但在 **Context 精细化管理** 方面还有提升空间。建议优先吸收 openclaw 的 Hybrid 搜索和压缩质量保障机制。

---

*报告生成时间: 2026-03-29*
*对比系统版本: ai-agent-platform (latest), learn-claude-code (main), openclaw (main)*
