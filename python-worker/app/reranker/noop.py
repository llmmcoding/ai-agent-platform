"""
空 Reranker (禁用时使用)
"""
from typing import List, Optional

from app.reranker.base import Reranker


class NoOpReranker(Reranker):
    """空 Reranker，直接返回原始结果"""

    async def rerank(
        self,
        query: str,
        documents: List[dict],
        top_k: Optional[int] = None
    ) -> List[dict]:
        """直接返回原始结果"""
        k = top_k or self.top_k
        for doc in documents[:k]:
            doc["rerank_score"] = doc.get("score", 0.0)
        return documents[:k]

    async def health_check(self) -> bool:
        return True
