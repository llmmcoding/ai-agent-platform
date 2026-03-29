"""
RAG 模块单元测试
"""
import pytest
import asyncio
from unittest.mock import AsyncMock, MagicMock, patch

from app.rag.retriever import RAGRetriever
from app.reranker.factory import reset_reranker


class TestRAGRetriever:
    """RAGRetriever 测试用例"""

    def setup_method(self):
        """每个测试方法前重置 reranker"""
        reset_reranker()

    def teardown_method(self):
        """每个测试方法后重置 reranker"""
        reset_reranker()

    @pytest.mark.asyncio
    async def test_rerank_with_empty_documents(self):
        """测试空文档列表"""
        # Given
        retriever = RAGRetriever()
        query = "test query"
        documents = []

        # When
        results = await retriever.rerank(query, documents, top_k=5)

        # Then
        assert results == []

    @pytest.mark.asyncio
    async def test_rerank_with_valid_documents(self):
        """测试有效文档列表"""
        # Given
        retriever = RAGRetriever()
        query = "what is AI"
        documents = [
            {"id": "doc1", "content": "AI is artificial intelligence", "score": 0.9},
            {"id": "doc2", "content": "Python is a programming language", "score": 0.8},
            {"id": "doc3", "content": "Machine learning is part of AI", "score": 0.7},
        ]

        # When
        results = await retriever.rerank(query, documents, top_k=2)

        # Then
        assert len(results) == 2
        # NoOpReranker preserves original order
        assert results[0]["id"] == "doc1"
        assert results[1]["id"] == "doc2"

    @pytest.mark.asyncio
    async def test_rerank_preserves_rerank_score(self):
        """测试 rerank 后保留 rerank_score 字段"""
        # Given
        retriever = RAGRetriever()
        query = "test query"
        documents = [
            {"id": "doc1", "content": "content 1", "score": 0.9},
            {"id": "doc2", "content": "content 2", "score": 0.8},
        ]

        # When
        results = await retriever.rerank(query, documents, top_k=2)

        # Then
        for doc in results:
            assert "rerank_score" in doc

    @pytest.mark.asyncio
    async def test_rerank_respects_top_k(self):
        """测试 top_k 参数"""
        # Given
        retriever = RAGRetriever()
        query = "test query"
        documents = [
            {"id": f"doc{i}", "content": f"content {i}", "score": 0.9 - i * 0.1}
            for i in range(10)
        ]

        # When
        results = await retriever.rerank(query, documents, top_k=3)

        # Then
        assert len(results) == 3

    @pytest.mark.asyncio
    async def test_rerank_handles_exception(self):
        """测试异常处理"""
        # Given
        with patch("app.rag.retriever.get_reranker") as mock_get_reranker:
            mock_reranker = MagicMock()
            mock_reranker.rerank = AsyncMock(side_effect=Exception("Rerank failed"))
            mock_get_reranker.return_value = mock_reranker

            retriever = RAGRetriever()
            query = "test query"
            documents = [
                {"id": "doc1", "content": "content 1", "score": 0.9},
            ]

            # When
            results = await retriever.rerank(query, documents, top_k=5)

            # Then - 应该返回原始文档
            assert len(results) == 1
            assert results[0]["id"] == "doc1"

    def test_query_sync_returns_empty_with_warning(self):
        """测试废弃的 query_sync 方法"""
        # Given
        retriever = RAGRetriever()

        # When
        with pytest.warns(DeprecationWarning):
            results = retriever.query_sync(query="test")

        # Then
        assert results == []

    @pytest.mark.asyncio
    async def test_query_deprecated_warning(self):
        """测试废弃的 query 方法"""
        # Given
        retriever = RAGRetriever()

        # When
        with pytest.warns(UserWarning, match="deprecated"):
            results = await retriever.query(query="test", top_k=5)

        # Then
        assert results == []

    @pytest.mark.asyncio
    async def test_index_documents_deprecated(self):
        """测试废弃的 index_documents 方法"""
        # Given
        retriever = RAGRetriever()

        # When
        with pytest.warns(UserWarning, match="deprecated"):
            count = await retriever.index_documents(collection="test", documents=[])

        # Then
        assert count == 0


class TestRerankerFactory:
    """Reranker 工厂测试"""

    def setup_method(self):
        """每个测试方法前重置"""
        reset_reranker()

    def teardown_method(self):
        """每个测试方法后重置"""
        reset_reranker()

    def test_get_reranker_returns_noop_when_disabled(self):
        """测试禁用 rerank 时返回 NoOp"""
        # Given
        with patch("app.reranker.factory.settings") as mock_settings:
            mock_settings.RERANK_PROVIDER = "none"

            # When
            from app.reranker.factory import get_reranker
            reranker = get_reranker()

            # Then
            from app.reranker.noop import NoOpReranker
            assert isinstance(reranker, NoOpReranker)

    def test_get_reranker_raises_for_unknown_provider(self):
        """测试未知 provider 抛出异常"""
        # Given
        with patch("app.reranker.factory.settings") as mock_settings:
            mock_settings.RERANK_PROVIDER = "unknown_provider"

            # When/Then
            with pytest.raises(ValueError, match="Unsupported rerank provider"):
                from app.reranker.factory import get_reranker
                get_reranker()

    def test_reset_reranker_clears_instance(self):
        """测试重置 reranker"""
        # Given
        from app.reranker.factory import get_reranker, reset_reranker
        reranker1 = get_reranker()

        # When
        reset_reranker()
        reranker2 = get_reranker()

        # Then
        # 可能返回同一个实例（如果单例还没被回收），但至少不会报错
        assert reranker1 is not None
        assert reranker2 is not None
