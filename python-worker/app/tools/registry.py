"""
工具注册器
"""
import logging
from typing import Dict, Callable, Any

logger = logging.getLogger(__name__)


class ToolRegistry:
    """工具注册器"""

    def __init__(self):
        self._tools: Dict[str, Callable] = {}
        self._descriptions: Dict[str, dict] = {}

    def register(self, name: str, description: str, executor: Callable, metadata: dict = None):
        """注册工具"""
        self._tools[name] = executor
        self._descriptions[name] = {
            "name": name,
            "description": description,
            "metadata": metadata or {}
        }
        logger.info(f"Tool registered: {name}")

    def get_executor(self, name: str) -> Callable:
        """获取工具执行器"""
        return self._tools.get(name)

    def get_description(self, name: str) -> dict:
        """获取工具描述"""
        return self._descriptions.get(name)

    def get_all_descriptions(self) -> list:
        """获取所有工具描述"""
        return list(self._descriptions.values())

    def has_tool(self, name: str) -> bool:
        """检查工具是否存在"""
        return name in self._tools


# 全局工具注册器实例
tool_registry = ToolRegistry()


# ==================== 内置工具 ====================

def web_search_executor(input_data: dict) -> str:
    """Web Search 工具 - 使用 DuckDuckGo 搜索"""
    import json
    from duckduckgo_search import DDGS

    query = input_data.get("query", "")
    max_results = input_data.get("max_results", 5)

    if not query:
        return json.dumps({"status": "error", "message": "query is required"})

    try:
        results = []
        with DDGS() as ddgs:
            for i, result in enumerate(ddgs.text(query, max_results=max_results)):
                if i >= max_results:
                    break
                results.append({
                    "title": result.get("title", ""),
                    "href": result.get("href", ""),
                    "body": result.get("body", "")[:500]  # 限制摘要长度
                })

        return json.dumps({
            "status": "success",
            "query": query,
            "count": len(results),
            "results": results
        }, ensure_ascii=False)
    except Exception as e:
        logger.error(f"Web search failed: {e}")
        return json.dumps({"status": "error", "message": str(e)})


def calculator_executor(input_data: dict) -> str:
    """计算器工具"""
    expression = input_data.get("expression", "")
    try:
        result = eval(expression)
        return str(result)
    except Exception as e:
        return f"Error: {e}"


def http_request_executor(input_data: dict) -> str:
    """HTTP 请求工具"""
    import httpx
    import asyncio

    url = input_data.get("url", "")
    method = input_data.get("method", "GET")
    headers = input_data.get("headers", {})
    body = input_data.get("body")

    async def _execute():
        async with httpx.AsyncClient() as client:
            if method == "GET":
                response = await client.get(url, headers=headers, timeout=30)
            elif method == "POST":
                response = await client.post(url, headers=headers, json=body, timeout=30)
            else:
                return f"Unsupported method: {method}"
            return response.text

    return asyncio.run(_execute())


# 注册内置工具
tool_registry.register(
    name="web_search",
    description="Search the web for information",
    executor=web_search_executor,
    metadata={"category": "search", "version": "1.0"}
)

tool_registry.register(
    name="calculator",
    description="Evaluate a mathematical expression",
    executor=calculator_executor,
    metadata={"category": "utility", "version": "1.0"}
)

tool_registry.register(
    name="http_request",
    description="Make HTTP requests to external APIs",
    executor=http_request_executor,
    metadata={"category": "api", "version": "1.0"}
)


def rag_index_memory_executor(input_data: dict) -> str:
    """RAG 索引记忆工具 - 将内容索引到 Milvus 向量库"""
    import json
    import asyncio

    user_id = input_data.get("user_id", "")
    content = input_data.get("content", "")
    collection = input_data.get("collection", "long_term_memory")

    if not content:
        return json.dumps({"status": "error", "message": "content is required"})

    try:
        from app.rag.retriever import RAGRetriever
        retriever = RAGRetriever()

        # 构建文档
        document = {
            "content": content,
            "metadata": {
                "user_id": user_id,
                "type": "memory",
                "indexed_at": asyncio.get_event_loop().time()
            }
        }

        # 同步执行索引
        count = retriever.query_sync(
            query=content,
            collection=collection,
            top_k=1
        )

        # 实际索引
        async def _index():
            return await retriever.index_documents(collection, [document])

        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        try:
            result = loop.run_until_complete(_index())
        finally:
            loop.close()

        return json.dumps({
            "status": "success",
            "indexed": result,
            "user_id": user_id
        })
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


tool_registry.register(
    name="rag_index_memory",
    description="Index memory content to Milvus vector database for long-term storage",
    executor=rag_index_memory_executor,
    metadata={"category": "rag", "version": "1.0"}
)
