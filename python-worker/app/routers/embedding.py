"""
Embedding API
负责生成 embedding 向量，返回给 Java 调用
"""
import logging
from typing import List

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from app.config import settings

logger = logging.getLogger(__name__)
router = APIRouter()

# Embedding 服务实例 (复用 MilvusVectorStore 的 embedding 逻辑)
_embedding_service = None


def get_embedding_service():
    """获取 Embedding 服务"""
    global _embedding_service
    if _embedding_service is None:
        _embedding_service = EmbeddingService()
    return _embedding_service


class EmbeddingService:
    """Embedding 生成服务"""

    def __init__(self):
        self._openai_client = None
        self._local_model = None

    async def get_embedding(self, text: str) -> List[float]:
        """获取文本的 embedding 向量"""
        if settings.EMBEDDING_PROVIDER == "openai":
            return await self._get_openai_embedding(text)
        else:
            return await self._get_local_embedding(text)

    async def _get_openai_embedding(self, text: str) -> List[float]:
        """使用 OpenAI API 获取 Embedding"""
        try:
            from openai import AsyncOpenAI

            if self._openai_client is None:
                self._openai_client = AsyncOpenAI(
                    api_key=settings.OPENAI_API_KEY,
                    base_url=settings.OPENAI_BASE_URL
                )

            response = await self._openai_client.embeddings.create(
                model=settings.OPENAI_EMBEDDING_MODEL,
                input=text
            )

            embedding = response.data[0].embedding
            logger.debug(f"Got OpenAI embedding, dim={len(embedding)}")
            return embedding

        except Exception as e:
            logger.error(f"OpenAI embedding failed: {e}, falling back to local")
            return await self._get_local_embedding(text)

    async def _get_local_embedding(self, text: str) -> List[float]:
        """使用本地模型获取 Embedding"""
        try:
            from sentence_transformers import SentenceTransformer

            if self._local_model is None:
                self._local_model = SentenceTransformer(settings.LOCAL_EMBEDDING_MODEL)

            embedding = self._local_model.encode(text).tolist()
            logger.debug(f"Got local embedding, dim={len(embedding)}")
            return embedding

        except Exception as e:
            logger.error(f"Local embedding failed: {e}, using zero vector")
            return [0.0] * settings.EMBEDDING_DIM


class EmbeddingRequest(BaseModel):
    text: str


class EmbeddingResponse(BaseModel):
    success: bool
    embedding: List[float] = []
    provider: str = ""
    error: str = ""


@router.post("/embedding", response_model=EmbeddingResponse)
async def generate_embedding(request: EmbeddingRequest) -> EmbeddingResponse:
    """生成 embedding 向量，返回给 Java"""
    import time
    start_time = time.time()

    try:
        logger.info(f"Generating embedding for text: {request.text[:50]}...")

        service = get_embedding_service()
        embedding = await service.get_embedding(request.text)

        query_time_ms = int((time.time() - start_time) * 1000)

        return EmbeddingResponse(
            success=True,
            embedding=embedding,
            provider=settings.EMBEDDING_PROVIDER
        )

    except Exception as e:
        logger.error(f"Embedding generation failed: {e}")
        return EmbeddingResponse(
            success=False,
            error=str(e)
        )


@router.post("/embedding/batch", response_model=EmbeddingResponse)
async def generate_embedding_batch(request: List[str]) -> EmbeddingResponse:
    """批量生成 embedding 向量"""
    try:
        service = get_embedding_service()
        embeddings = []

        for text in request:
            embedding = await service.get_embedding(text)
            embeddings.append(embedding)

        # 返回第一个 embedding（通常批量用于相同文本的不同表述）
        return EmbeddingResponse(
            success=True,
            embedding=embeddings[0] if embeddings else [],
            provider=settings.EMBEDDING_PROVIDER
        )

    except Exception as e:
        logger.error(f"Batch embedding generation failed: {e}")
        return EmbeddingResponse(
            success=False,
            error=str(e)
        )
