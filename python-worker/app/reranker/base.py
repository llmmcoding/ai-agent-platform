"""
Reranker 抽象基类
"""
from abc import ABC, abstractmethod
from typing import List, Optional


class Reranker(ABC):
    """Rerank 抽象接口"""

    def __init__(self, top_k: int = 5):
        """
        Args:
            top_k: 最终返回的文档数量
        """
        self.top_k = top_k

    @abstractmethod
    async def rerank(
        self,
        query: str,
        documents: List[dict],
        top_k: Optional[int] = None
    ) -> List[dict]:
        """
        对文档进行重排序

        Args:
            query: 查询文本
            documents: 候选文档列表，每项包含 id, content, score, metadata
            top_k: 返回数量，默认使用初始化时的 top_k

        Returns:
            List[dict]: 重排序后的文档列表，包含 rerank_score 字段
        """
        pass

    @abstractmethod
    async def health_check(self) -> bool:
        """检查 rerank 模型是否可用"""
        pass
