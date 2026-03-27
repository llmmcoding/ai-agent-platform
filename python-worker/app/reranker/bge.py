"""
BGE-rerank 本地模型实现
"""
import logging
from typing import List, Optional

from app.config import settings
from app.reranker.base import Reranker

logger = logging.getLogger(__name__)


class BGEReranker(Reranker):
    """BGE-rerank 本地模型实现"""

    def __init__(self, top_k: int = 5):
        super().__init__(top_k)
        self._model = None

    async def _get_model(self):
        """获取 BGE rerank 模型 (延迟加载)"""
        if self._model is None:
            try:
                from sentence_transformers import CrossEncoder
                self._model = CrossEncoder(settings.BGE_RERANK_MODEL)
                logger.info(f"Loaded BGE rerank model: {settings.BGE_RERANK_MODEL}")
            except Exception as e:
                logger.error(f"Failed to load BGE rerank model: {e}")
                raise
        return self._model

    async def rerank(
        self,
        query: str,
        documents: List[dict],
        top_k: Optional[int] = None
    ) -> List[dict]:
        """使用 BGE-rerank 模型重排序"""
        k = top_k or self.top_k

        if not documents:
            return []

        try:
            model = await self._get_model()

            # 准备输入: [(query, document), ...]
            pairs = [(query, doc["content"]) for doc in documents]

            # 批量预测
            scores = model.predict(pairs)

            # 重建结果
            reranked = []
            for i, doc in enumerate(documents):
                reranked.append({
                    **doc,
                    "rerank_score": float(scores[i])
                })

            # 按 rerank_score 排序
            reranked.sort(key=lambda x: x["rerank_score"], reverse=True)
            return reranked[:k]

        except Exception as e:
            logger.error(f"BGE rerank failed: {e}")
            return documents[:k]

    async def health_check(self) -> bool:
        """检查 BGE 模型是否可用"""
        try:
            model = await self._get_model()
            return model is not None
        except Exception:
            return False
