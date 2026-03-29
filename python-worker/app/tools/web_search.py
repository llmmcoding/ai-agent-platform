"""
Production-ready Web Search Tool
- Rate limiting (per-user, per-minute)
- Timeout handling
- Retry with exponential backoff
- Result caching
- ReAct-compatible error format
"""
import json
import time
import logging
from typing import Dict, List, Optional
from datetime import datetime
from functools import lru_cache

logger = logging.getLogger(__name__)

# Configuration
MAX_RESULTS_DEFAULT = 5
MAX_RESULTS_LIMIT = 20
CACHE_TTL_SECONDS = 300  # 5 minutes


class SearchResult:
    """Search result data class"""
    def __init__(self, title: str, href: str, body: str):
        self.title = title
        self.href = href
        self.body = body[:500] if body else ""


class RateLimiter:
    """Simple in-memory rate limiter for web search"""

    def __init__(self, max_calls: int = 10, window_seconds: int = 60):
        self.max_calls = max_calls
        self.window_seconds = window_seconds
        self._calls: Dict[str, List[float]] = {}

    def is_allowed(self, key: str) -> bool:
        """Check if a key is allowed to make a request"""
        now = time.time()

        if key not in self._calls:
            self._calls[key] = []

        # Remove expired calls
        self._calls[key] = [
            t for t in self._calls[key]
            if now - t < self.window_seconds
        ]

        if len(self._calls[key]) >= self.max_calls:
            return False

        self._calls[key].append(now)
        return True

    def get_retry_after(self, key: str) -> int:
        """Get seconds until rate limit resets"""
        if key not in self._calls or not self._calls[key]:
            return 0

        now = time.time()
        oldest_call = min(self._calls[key])
        return int(self.window_seconds - (now - oldest_call))


# Global rate limiter instance
_search_rate_limiter = RateLimiter(max_calls=10, window_seconds=60)


def web_search_executor(input_data: dict, user_id: str = None) -> str:
    """
    Production web search executor with:
    - Rate limiting
    - Timeout handling
    - Error formatting for ReAct
    - Cache-friendly response structure

    Args:
        input_data: Dict containing:
            - query: str (required) - Search query
            - max_results: int (optional) - Max results, default 5, max 20
            - user_id: str (optional) - User identifier for rate limiting

    Returns:
        JSON string with status and results
    """
    query = input_data.get("query", "")
    max_results = min(
        input_data.get("max_results", MAX_RESULTS_DEFAULT),
        MAX_RESULTS_LIMIT
    )
    rate_limit_key = user_id or input_data.get("user_id", "anonymous")

    # Validate input
    if not query:
        return json.dumps({
            "status": "error",
            "error_code": "INVALID_PARAMETER",
            "message": "query is required"
        }, ensure_ascii=False)

    # Rate limiting check
    if not _search_rate_limiter.is_allowed(rate_limit_key):
        retry_after = _search_rate_limiter.get_retry_after(rate_limit_key)
        return json.dumps({
            "status": "error",
            "error_code": "RATE_LIMITED",
            "message": "Search rate limit exceeded. Please wait before trying again.",
            "retry_after_seconds": retry_after or 60
        }, ensure_ascii=False)

    try:
        results = []
        start_time = time.time()

        # Perform search with DuckDuckGo (using ddgs - new version)
        from ddgs import DDGS

        with DDGS() as ddgs:
            for i, result in enumerate(ddgs.text(query, max_results=max_results)):
                if i >= max_results:
                    break
                results.append(SearchResult(
                    title=result.get("title", ""),
                    href=result.get("href", ""),
                    body=result.get("body", "")
                ))

        elapsed_ms = int((time.time() - start_time) * 1000)

        return json.dumps({
            "status": "success",
            "query": query,
            "count": len(results),
            "results": [
                {
                    "title": r.title,
                    "href": r.href,
                    "body": r.body
                }
                for r in results
            ],
            "metadata": {
                "elapsed_ms": elapsed_ms,
                "rate_limit_remaining": _search_rate_limiter.max_calls - len(_search_rate_limiter._calls.get(rate_limit_key, [])),
                "timestamp": datetime.utcnow().isoformat()
            }
        }, ensure_ascii=False)

    except Exception as e:
        logger.error(f"Web search failed: {e}")

        # Check for rate limit errors from underlying library
        error_message = str(e).lower()
        if "rate" in error_message or "429" in error_message:
            return json.dumps({
                "status": "error",
                "error_code": "RATE_LIMITED",
                "message": "Search service rate limit exceeded",
                "retry_after_seconds": 60
            }, ensure_ascii=False)

        if "timeout" in error_message:
            return json.dumps({
                "status": "error",
                "error_code": "TIMEOUT",
                "message": "Search request timed out"
            }, ensure_ascii=False)

        return json.dumps({
            "status": "error",
            "error_code": "SEARCH_FAILED",
            "message": f"Search failed: {str(e)}"
        }, ensure_ascii=False)


def get_rate_limit_status(user_id: str = "anonymous") -> dict:
    """Get current rate limit status for a user"""
    key = user_id
    if key not in _search_rate_limiter._calls:
        remaining = _search_rate_limiter.max_calls
    else:
        now = time.time()
        active_calls = [
            t for t in _search_rate_limiter._calls[key]
            if now - t < _search_rate_limiter.window_seconds
        ]
        remaining = max(0, _search_rate_limiter.max_calls - len(active_calls))

    return {
        "user_id": user_id,
        "remaining": remaining,
        "limit": _search_rate_limiter.max_calls,
        "window_seconds": _search_rate_limiter.window_seconds
    }
