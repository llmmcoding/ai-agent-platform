"""
Cohere Rerank API 实现
"""
import logging
from typing import List, Optional

import httpx

from app.config import settings
from app.reranker.base import Reranker

logger = logging.getLogger(__name__)


class CohereReranker(Reranker):
    """Cohere Rerank API 实现"""

    def __init__(self, top_k: int = 5):
        super().__init__(top_k)
        self._client = None

    async def _get_client(self) -> httpx.AsyncClient:
        """获取 Cohere HTTP 客户端"""
        if self._client is None:
            self._client = httpx.AsyncClient(
                base_url="https://api.cohere.ai",
                headers={
                    "Authorization": f"Bearer {settings.COHERE_API_KEY}",
                    "Content-Type": "application/json"
                },
                timeout=60.0
            )
        return self._client

    async def rerank(
        self,
        query: str,
        documents: List[dict],
        top_k: Optional[int] = None
    ) -> List[dict]:
        """使用 Cohere Rerank API 重排序"""
        k = top_k or self.top_k

        if not documents:
            return []

        try:
            client = await self._get_client()

            # 提取文档内容
            doc_texts = [doc["content"] for doc in documents]
            doc_ids = [doc["id"] for doc in documents]

            # 调用 Cohere Rerank API
            response = await client.post(
                "/v1/rerank",
                json={
                    "query": query,
                    "documents": doc_texts,
                    "top_n": k,
                    "model": settings.COHERE_RERANK_MODEL,
                    "return_documents": False
                }
            )
            response.raise_for_status()

            # 解析响应并重建结果
            rerank_results = response.json().get("results", [])
            score_map = {r["index"]: r["relevance_score"] for r in rerank_results}

            reranked = []
            for i, doc_id in enumerate(doc_ids):
                original_doc = documents[i]
                rerank_score = score_map.get(i, original_doc.get("score", 0.0))

                reranked.append({
                    **original_doc,
                    "rerank_score": rerank_score
                })

            # 按 rerank_score 排序
            reranked.sort(key=lambda x: x["rerank_score"], reverse=True)
            return reranked[:k]

        except Exception as e:
            logger.error(f"Cohere rerank failed: {e}")
            # 失败时返回原始结果
            return documents[:k]

    async def health_check(self) -> bool:
        """检查 Cohere API 是否可用"""
        try:
            client = await self._get_client()
            response = await client.get("/v1/models")
            return response.status_code == 200
        except Exception as e:
            logger.warning(f"Cohere health check failed: {e}")
            return False
