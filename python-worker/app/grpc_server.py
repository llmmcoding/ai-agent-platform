"""
gRPC Server for Python Worker
提供 Tool 执行和 RAG 能力的 gRPC 接口
"""
import asyncio
import logging
from concurrent import futures
from typing import Any, Dict

import grpc
from grpc import aio

from app.config import settings
from app.tools.registry import tool_registry
from app.rag.retriever import RAGRetriever

# 生成或导入 proto 模块（实际项目中通过 protoc 生成）
# 这里提供等效的实现

logger = logging.getLogger(__name__)


class ToolServicer:
    """Tool 服务实现"""

    async def ExecuteTool(self, request, context):
        """执行工具"""
        import json
        start_time = asyncio.get_event_loop().time()

        tool_name = request.tool_name
        input_json = request.input_json

        logger.info(f"gRPC ExecuteTool: {tool_name}, trace_id: {request.trace_id}")

        try:
            # 解析输入
            input_data = json.loads(input_json) if input_json else {}

            # 获取工具执行器
            executor = tool_registry.get_executor(tool_name)
            if not executor:
                return ToolExecuteResponse(
                    success=False,
                    result="",
                    error=f"Tool not found: {tool_name}",
                    duration_ms=0
                )

            # 执行工具
            if asyncio.iscoroutinefunction(executor):
                result = await executor(input_data)
            else:
                result = executor(input_data)

            duration_ms = int((asyncio.get_event_loop().time() - start_time) * 1000)

            return ToolExecuteResponse(
                success=True,
                result=str(result),
                error="",
                duration_ms=duration_ms
            )

        except Exception as e:
            logger.error(f"Tool execution failed: {tool_name}", exc_info=e)
            duration_ms = int((asyncio.get_event_loop().time() - start_time) * 1000)

            return ToolExecuteResponse(
                success=False,
                result="",
                error=str(e),
                duration_ms=duration_ms
            )

    async def ExecuteToolStream(self, request, context):
        """流式执行工具（暂不支持）"""
        yield ToolExecuteResponse(
            success=False,
            result="",
            error="Streaming not implemented",
            duration_ms=0
        )


class RAGServicer:
    """RAG 服务实现"""

    def __init__(self):
        self.retriever = RAGRetriever()

    async def Query(self, request, context):
        """RAG 查询"""
        import json
        start_time = asyncio.get_event_loop().time()

        try:
            results = await self.retriever.query(
                query=request.query,
                collection=request.collection,
                top_k=request.top_k if request.top_k > 0 else 5
            )

            query_time_ms = int((asyncio.get_event_loop().time() - start_time) * 1000)

            # 转换结果
            rag_results = []
            for r in results:
                rag_results.append(RAGResult(
                    id=r.get("id", ""),
                    content=r.get("content", ""),
                    score=r.get("score", 0.0),
                    metadata=r.get("metadata", {})
                ))

            return RAGQueryResponse(
                success=True,
                results=rag_results,
                error="",
                query_time_ms=query_time_ms
            )

        except Exception as e:
            logger.error(f"RAG query failed", exc_info=e)
            query_time_ms = int((asyncio.get_event_loop().time() - start_time) * 1000)

            return RAGQueryResponse(
                success=False,
                results=[],
                error=str(e),
                query_time_ms=query_time_ms
            )

    async def IndexDocuments(self, request, context):
        """索引文档"""
        try:
            documents = []
            for doc in request.documents:
                documents.append({
                    "id": doc.id,
                    "content": doc.content,
                    "metadata": dict(doc.metadata) if doc.metadata else {}
                })

            count = await self.retriever.index_documents(
                collection=request.collection,
                documents=documents
            )

            return IndexDocumentsResponse(
                success=True,
                indexed_count=count,
                error=""
            )

        except Exception as e:
            logger.error(f"Document indexing failed", exc_info=e)
            return IndexDocumentsResponse(
                success=False,
                indexed_count=0,
                error=str(e)
            )


async def serve(port: int = 9090):
    """启动 gRPC 服务器"""
    server = aio.server(
        futures.ThreadPoolExecutor(max_workers=10),
        options=[
            ('grpc.max_send_message_length', 50 * 1024 * 1024),
            ('grpc.max_receive_message_length', 50 * 1024 * 1024),
        ]
    )

    # 添加服务（实际使用生成的 proto stubs）
    # tool_service.add_ToolServiceServicer_to_server(ToolServicer(), server)
    # rag_service.add_RAGServiceServicer_to_server(RAGServicer(), server)

    listen_addr = f'[::]:{port}'
    server.add_insecure_port(listen_addr)

    logger.info(f"Starting gRPC server on {listen_addr}")
    await server.start()

    try:
        await server.wait_for_termination()
    except KeyboardInterrupt:
        await server.stop(5)


if __name__ == '__main__':
    logging.basicConfig(level=logging.INFO)
    asyncio.run(serve(settings.GRPC_PORT))


# Proto 消息定义（用于 type hints）
class ToolExecuteRequest:
    def __init__(self, tool_name="", input_json="", trace_id="", metadata=None):
        self.tool_name = tool_name
        self.input_json = input_json
        self.trace_id = trace_id
        self.metadata = metadata or {}


class ToolExecuteResponse:
    def __init__(self, success=False, result="", error="", duration_ms=0):
        self.success = success
        self.result = result
        self.error = error
        self.duration_ms = duration_ms


class RAGQueryRequest:
    def __init__(self, query="", collection="default", top_k=5, filters=None):
        self.query = query
        self.collection = collection
        self.top_k = top_k
        self.filters = filters or {}


class RAGQueryResponse:
    def __init__(self, success=False, results=None, error="", query_time_ms=0):
        self.success = success
        self.results = results or []
        self.error = error
        self.query_time_ms = query_time_ms


class RAGResult:
    def __init__(self, id="", content="", score=0.0, metadata=None):
        self.id = id
        self.content = content
        self.score = score
        self.metadata = metadata or {}


class IndexDocumentsRequest:
    def __init__(self, collection="", documents=None):
        self.collection = collection
        self.documents = documents or []


class Document:
    def __init__(self, id="", content="", metadata=None):
        self.id = id
        self.content = content
        self.metadata = metadata or {}


class IndexDocumentsResponse:
    def __init__(self, success=False, indexed_count=0, error=""):
        self.success = success
        self.indexed_count = indexed_count
        self.error = error
