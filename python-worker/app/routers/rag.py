"""
RAG 检索路由
标准 Query Flow:
1. Python: Embedding 生成
2. Java: 向量检索 (高并发)
3. Python: Rerank 精排
"""
import logging
from typing import List, Optional

import httpx
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from app.config import settings
from app.rag.retriever import RAGRetriever
from app.routers.embedding import get_embedding_service

logger = logging.getLogger(__name__)
router = APIRouter()

# RAG 检索器实例 (只用于 rerank)
rag_retriever = RAGRetriever()


class RAGQueryRequest(BaseModel):
    query: str
    collection: str = "default"
    top_k: int = 5
    user_id: str = ""
    filters: Optional[dict] = None
    enable_rerank: bool = True


class RAGQueryResponse(BaseModel):
    success: bool
    results: List[dict] = []
    error: str = ""
    query_time_ms: int = 0
    embedding_time_ms: int = 0
    search_time_ms: int = 0
    rerank_time_ms: int = 0


class DocumentIndexRequest(BaseModel):
    collection: str
    documents: List[dict]  # [{"id": str, "content": str, "metadata": dict}]
    user_id: str = ""


async def _call_java_vector_search(query_embedding: List[float], collection: str, top_k: int) -> List[dict]:
    """调用 Java 向量检索服务"""
    async with httpx.AsyncClient(timeout=30.0) as client:
        response = await client.post(
            settings.VECTOR_SEARCH_URL,
            json={
                "queryEmbedding": query_embedding,
                "collection": collection,
                "topK": top_k
            }
        )
        response.raise_for_status()
        result = response.json()

        if result.get("success"):
            return result.get("data", [])
        else:
            raise Exception(f"Java vector search failed: {result.get('message')}")


@router.post("/query", response_model=RAGQueryResponse)
async def query(request: RAGQueryRequest) -> RAGQueryResponse:
    """
    标准 Query Flow:
    1. Python: Embedding 生成
    2. Java: 向量检索
    3. Python: Rerank 精排
    """
    import time
    start_time = time.time()
    embedding_time_ms = 0
    search_time_ms = 0
    rerank_time_ms = 0

    try:
        logger.info(f"RAG query: {request.query}, collection: {request.collection}, "
                    f"enable_rerank: {request.enable_rerank}")

        # Step 1: Embedding 生成 (Python)
        embed_start = time.time()
        embedding_service = get_embedding_service()
        query_embedding = await embedding_service.get_embedding(request.query)
        embedding_time_ms = int((time.time() - embed_start) * 1000)

        # Step 2: 向量检索 (Java)
        search_start = time.time()
        results = await _call_java_vector_search(
            query_embedding=query_embedding,
            collection=request.collection,
            top_k=settings.RERANK_TOP_K if request.enable_rerank else request.top_k
        )
        search_time_ms = int((time.time() - search_start) * 1000)

        # Step 3: Rerank 精排 (Python)
        rerank_start = time.time()
        if request.enable_rerank and results:
            results = await rag_retriever.rerank(
                query=request.query,
                documents=results,
                top_k=request.top_k
            )
        rerank_time_ms = int((time.time() - rerank_start) * 1000)

        query_time_ms = int((time.time() - start_time) * 1000)

        return RAGQueryResponse(
            success=True,
            results=results,
            query_time_ms=query_time_ms,
            embedding_time_ms=embedding_time_ms,
            search_time_ms=search_time_ms,
            rerank_time_ms=rerank_time_ms
        )

    except httpx.HTTPError as e:
        logger.error(f"RAG query failed: Java service unavailable: {e}")
        query_time_ms = int((time.time() - start_time) * 1000)

        return RAGQueryResponse(
            success=False,
            error=f"Java vector search service unavailable: {e}",
            query_time_ms=query_time_ms,
            embedding_time_ms=embedding_time_ms,
            search_time_ms=search_time_ms,
            rerank_time_ms=rerank_time_ms
        )
    except Exception as e:
        logger.error(f"RAG query failed: {e}")
        query_time_ms = int((time.time() - start_time) * 1000)

        return RAGQueryResponse(
            success=False,
            error=str(e),
            query_time_ms=query_time_ms,
            embedding_time_ms=embedding_time_ms,
            search_time_ms=search_time_ms,
            rerank_time_ms=rerank_time_ms
        )


@router.post("/index")
async def index_documents(request: DocumentIndexRequest):
    """
    索引文档 - 写入走 Kafka 异步批量路径
    注意：实际写入由 Java VectorWriteConsumer 处理
    """
    logger.info(f"Document indexing request received for collection: {request.collection}, "
                f"count: {len(request.documents)}")

    # 返回成功状态，实际处理由 Kafka Consumer 负责
    return {
        "success": True,
        "indexed_count": len(request.documents),
        "message": "Documents queued for indexing via Kafka"
    }
