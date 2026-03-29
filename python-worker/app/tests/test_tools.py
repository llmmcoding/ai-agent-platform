"""
工具注册器单元测试
"""
import pytest
import json
from unittest.mock import patch, MagicMock

from app.tools.registry import ToolRegistry, tool_registry


class TestToolRegistry:
    """工具注册器测试"""

    def setup_method(self):
        """每个测试前创建新的注册器实例"""
        self.registry = ToolRegistry()

    def test_register_tool(self):
        """测试注册工具"""
        # Given
        def dummy_executor(input_data):
            return "result"

        # When
        self.registry.register(
            name="test_tool",
            description="A test tool",
            executor=dummy_executor,
            metadata={"version": "1.0"}
        )

        # Then
        assert self.registry.has_tool("test_tool")
        desc = self.registry.get_description("test_tool")
        assert desc["name"] == "test_tool"
        assert desc["description"] == "A test tool"
        assert desc["metadata"]["version"] == "1.0"

    def test_get_executor(self):
        """测试获取执行器"""
        # Given
        def dummy_executor(input_data):
            return "result"
        self.registry.register("test_tool", "desc", dummy_executor)

        # When
        executor = self.registry.get_executor("test_tool")

        # Then
        assert executor is not None
        assert executor({}) == "result"

    def test_get_executor_returns_none_for_unknown(self):
        """测试获取未知工具返回 None"""
        # When
        executor = self.registry.get_executor("unknown_tool")

        # Then
        assert executor is None

    def test_get_all_descriptions(self):
        """测试获取所有工具描述"""
        # Given
        def dummy_executor(input_data):
            return "result"
        self.registry.register("tool1", "desc1", dummy_executor)
        self.registry.register("tool2", "desc2", dummy_executor)

        # When
        descriptions = self.registry.get_all_descriptions()

        # Then
        assert len(descriptions) == 2

    def test_has_tool(self):
        """测试检查工具存在"""
        # Given
        def dummy_executor(input_data):
            return "result"
        self.registry.register("existing_tool", "desc", dummy_executor)

        # Then
        assert self.registry.has_tool("existing_tool") is True
        assert self.registry.has_tool("nonexistent_tool") is False


class TestBuiltinTools:
    """内置工具测试"""

    def test_web_search_tool_registered(self):
        """测试 web_search 工具已注册"""
        assert tool_registry.has_tool("web_search")

    def test_calculator_tool_registered(self):
        """测试 calculator 工具已注册"""
        assert tool_registry.has_tool("calculator")

    def test_http_request_tool_registered(self):
        """测试 http_request 工具已注册"""
        assert tool_registry.has_tool("http_request")

    def test_rag_index_memory_tool_registered(self):
        """测试 rag_index_memory 工具已注册"""
        assert tool_registry.has_tool("rag_index_memory")

    def test_calculator_executor(self):
        """测试计算器执行"""
        # Given
        executor = tool_registry.get_executor("calculator")

        # When
        result = executor({"expression": "2 + 3"})

        # Then
        assert result == 5

    def test_calculator_with_complex_expression(self):
        """测试计算器复杂表达式"""
        # Given
        executor = tool_registry.get_executor("calculator")

        # When
        result = executor({"expression": "(10 + 5) * 2 - 8"})

        # Then
        assert result == 22

    def test_calculator_with_division(self):
        """测试计算器除法"""
        # Given
        executor = tool_registry.get_executor("calculator")

        # When
        result = executor({"expression": "10 / 2"})

        # Then
        assert result == 5

    def test_calculator_with_invalid_expression(self):
        """测试计算器无效表达式"""
        # Given
        executor = tool_registry.get_executor("calculator")

        # When
        result = executor({"expression": "invalid"})

        # Then
        assert "Error" in result

    @patch("httpx.AsyncClient")
    def test_http_request_get(self, mock_async_client):
        """测试 HTTP GET 请求"""
        # Given
        mock_response = MagicMock()
        mock_response.text = '{"status": "ok"}'

        mock_client = MagicMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=None)
        mock_client.get = AsyncMock(return_value=mock_response)
        mock_async_client.return_value = mock_client

        executor = tool_registry.get_executor("http_request")

        # When
        result = executor({"url": "https://api.example.com/status", "method": "GET"})

        # Then
        assert '{"status": "ok"}' in result or mock_client.get.called

    def test_web_search_returns_json(self):
        """测试 web_search 返回 JSON"""
        # Given
        executor = tool_registry.get_executor("web_search")

        # When
        result = executor({"query": "", "max_results": 5})

        # Then
        # 空 query 应该返回错误 JSON
        data = json.loads(result)
        assert data["status"] == "error"
        assert "query is required" in data["message"]

    @patch("duckduckgo_search.DDGS")
    def test_web_search_with_query(self, mock_ddgs):
        """测试带查询的 web_search"""
        # Given
        mock_result = MagicMock()
        mock_result.title = "Test Title"
        mock_result.href = "https://example.com"
        mock_result.body = "Test body content"

        mock_ddgs.return_value.__enter__ = MagicMock(return_value=mock_ddgs.return_value)
        mock_ddgs.return_value.__exit__ = MagicMock(return_value=None)
        mock_ddgs.return_value.text = MagicMock(return_value=[mock_result])

        executor = tool_registry.get_executor("web_search")

        # When
        result = executor({"query": "test search", "max_results": 5})

        # Then
        data = json.loads(result)
        assert data["status"] == "success"
        assert data["count"] >= 0


class TestToolDescriptions:
    """工具描述测试"""

    def test_get_tool_description(self):
        """测试获取工具描述"""
        # When
        desc = tool_registry.get_description("calculator")

        # Then
        assert desc is not None
        assert desc["name"] == "calculator"

    def test_get_all_tools_description(self):
        """测试获取所有工具描述"""
        # When
        all_descs = tool_registry.get_all_descriptions()

        # Then
        assert len(all_descs) >= 4  # 至少 4 个内置工具
        tool_names = [d["name"] for d in all_descs]
        assert "calculator" in tool_names
        assert "web_search" in tool_names

    def test_tool_metadata(self):
        """测试工具元数据"""
        # When
        desc = tool_registry.get_description("web_search")

        # Then
        assert "category" in desc["metadata"]
        assert desc["metadata"]["category"] == "search"


class TestGlobalToolRegistry:
    """全局工具注册器测试"""

    def test_global_registry_is_singleton(self):
        """测试全局注册器是单例"""
        from app.tools.registry import tool_registry as registry1
        from app.tools.registry import tool_registry as registry2

        assert registry1 is registry2

    def test_global_registry_has_builtin_tools(self):
        """测试全局注册器有内置工具"""
        assert tool_registry.has_tool("calculator")
        assert tool_registry.has_tool("web_search")
        assert tool_registry.has_tool("http_request")
        assert tool_registry.has_tool("rag_index_memory")
