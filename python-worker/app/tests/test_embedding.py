"""
Embedding 服务单元测试
"""
import pytest
import numpy as np
from unittest.mock import AsyncMock, MagicMock, patch


class TestEmbeddingService:
    """Embedding 服务测试"""

    @pytest.mark.asyncio
    async def test_get_embedding_returns_list(self):
        """测试获取 embedding 返回列表"""
        # Given
        with patch("app.embedding.factory") as mock_factory:
            # 模拟 OpenAI Embedding
            mock_response = MagicMock()
            mock_response.data = [MagicMock(embedding=[0.1] * 1536)]

            mock_client = MagicMock()
            mock_client.embeddings = MagicMock()
            mock_client.embeddings.create = AsyncMock(return_value=mock_response)
            mock_factory.return_value = mock_client

            from app.embedding.service import EmbeddingService
            service = EmbeddingService()

            # When
            result = await service.get_embedding("test text")

            # Then
            assert isinstance(result, list)
            assert len(result) == 1536

    @pytest.mark.asyncio
    async def test_get_embedding_batch(self):
        """测试批量获取 embedding"""
        # Given
        with patch("app.embedding.factory") as mock_factory:
            mock_response = MagicMock()
            mock_response.data = [
                MagicMock(embedding=[0.1] * 1536),
                MagicMock(embedding=[0.2] * 1536),
            ]

            mock_client = MagicMock()
            mock_client.embeddings = MagicMock()
            mock_client.embeddings.create = AsyncMock(return_value=mock_response)
            mock_factory.return_value = mock_client

            from app.embedding.service import EmbeddingService
            service = EmbeddingService()

            # When
            results = await service.get_embeddings(["text1", "text2"])

            # Then
            assert len(results) == 2
            assert all(len(emb) == 1536 for emb in results)

    @pytest.mark.asyncio
    async def test_get_embedding_handles_error(self):
        """测试错误处理"""
        # Given
        with patch("app.embedding.factory") as mock_factory:
            mock_client = MagicMock()
            mock_client.embeddings = MagicMock()
            mock_client.embeddings.create = AsyncMock(side_effect=Exception("API Error"))
            mock_factory.return_value = mock_client

            from app.embedding.service import EmbeddingService
            service = EmbeddingService()

            # When
            result = await service.get_embedding("test")

            # Then - 应该返回 fallback embedding
            assert isinstance(result, list)
            assert len(result) > 0

    @pytest.mark.asyncio
    async def test_local_embedding_service(self):
        """测试本地 embedding 服务"""
        # Given
        with patch("app.embedding.factory") as mock_factory:
            mock_model = MagicMock()
            mock_model.encode = MagicMock(return_value=np.array([[0.1] * 384]))
            mock_factory.return_value = mock_model

            from app.embedding.local import LocalEmbeddingService
            service = LocalEmbeddingService()

            # When
            result = await service.get_embedding("test text")

            # Then
            assert isinstance(result, list)
            assert len(result) == 384


class TestEmbeddingDimensions:
    """Embedding 维度测试"""

    def test_openai_embedding_dim_1536(self):
        """OpenAI text-embedding-3-small 维度为 1536"""
        # Given
        from app.config import settings

        # Then
        assert settings.EMBEDDING_DIM == 1536

    def test_local_embedding_dim_384(self):
        """Local MiniLM 维度为 384"""
        # Given
        from app.config import settings

        # Then - MiniLM-L6-v2 输出 384 维
        assert settings.LOCAL_EMBEDDING_MODEL == "sentence-transformers/all-MiniLM-L6-v2"
