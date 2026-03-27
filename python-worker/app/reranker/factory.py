"""
Reranker 工厂类
"""
import logging
from typing import Optional

from app.config import settings
from app.reranker.base import Reranker

logger = logging.getLogger(__name__)

# 全局 reranker 实例
_reranker: Optional[Reranker] = None


def get_reranker() -> Reranker:
    """获取 Reranker 实例 (单例)"""
    global _reranker

    if _reranker is None:
        provider = settings.RERANK_PROVIDER.lower()

        if provider == "none" or provider == "":
            from app.reranker.noop import NoOpReranker
            _reranker = NoOpReranker()
            logger.info("Using NoOp Reranker (disabled)")

        elif provider == "cohere":
            from app.reranker.cohere import CohereReranker
            _reranker = CohereReranker()
            logger.info("Using Cohere Reranker")

        elif provider == "bge":
            from app.reranker.bge import BGEReranker
            _reranker = BGEReranker()
            logger.info("Using BGE Reranker")

        elif provider == "cross_encoder":
            from app.reranker.cross_encoder import CrossEncoderReranker
            _reranker = CrossEncoderReranker()
            logger.info("Using Cross-Encoder Reranker")

        else:
            raise ValueError(f"Unsupported rerank provider: {provider}")

    return _reranker


def reset_reranker():
    """重置 Reranker 实例 (用于测试)"""
    global _reranker
    _reranker = None
