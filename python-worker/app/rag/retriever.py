"""
RAG 检索器
只负责 Rerank 精排（向量检索已迁移到 Java 侧）
"""
import logging
from typing import List, Optional

from app.config import settings
from app.reranker.factory import get_reranker

logger = logging.getLogger(__name__)


class RAGRetriever:
    """
    RAG 检索器 - 只负责 Rerank 精排
    注意：向量检索已迁移到 Java 侧 (MilvusService)
    """

    def __init__(self):
        self._reranker = get_reranker()

    async def rerank(
            self,
            query: str,
            documents: List[dict],
            top_k: int = 5
    ) -> List[dict]:
        """
        对文档进行 Rerank 精排

        Args:
            query: 查询文本
            documents: 候选文档列表（来自 Java 向量检索）
            top_k: 最终返回数量
        """
        if not documents:
            return []

        try:
            results = await self._reranker.rerank(
                query=query,
                documents=documents,
                top_k=top_k
            )
            return results
        except Exception as e:
            logger.error(f"Rerank failed: {e}, returning original order")
            return documents[:top_k]

    async def query(self, **kwargs) -> List[dict]:
        """
        兼容旧接口 - 直接调用 rerank
        注意：实际向量检索在 Java 侧完成
        """
        query = kwargs.get("query", "")
        top_k = kwargs.get("top_k", 5)
        enable_rerank = kwargs.get("enable_rerank", True)

        # 这个方法不再被使用，向量检索在 Java 侧
        logger.warning("RAGRetriever.query() is deprecated, use Java /api/v1/rag/search for vector search")
        return []

    def query_sync(self, **kwargs) -> List[dict]:
        """同步接口 - 兼容旧代码"""
        import asyncio
        return asyncio.run(self.query(**kwargs))

    async def index_documents(self, **kwargs) -> int:
        """
        索引文档 - 已迁移到 Java 侧
        通过 Kafka 异步批量写入
        """
        logger.warning("RAGRetriever.index_documents() is deprecated, use Java Kafka producer for vector write")
        return 0

    def _mock_results(self, query: str, top_k: int) -> List[dict]:
        """Mock 结果（不应再被调用）"""
        return [
            {
                "id": f"doc_{i}",
                "content": f"Mock content for: {query}",
                "score": 0.9 - i * 0.1,
                "rerank_score": 0.9 - i * 0.1,
                "metadata": {"source": "mock"}
            }
            for i in range(min(top_k, 3))
        ]
