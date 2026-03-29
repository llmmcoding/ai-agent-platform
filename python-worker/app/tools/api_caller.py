"""
Generic API Caller Tool
- Supports all HTTP methods (GET, POST, PUT, DELETE, PATCH)
- Request/response transformation
- OAuth token injection
- SSRF protection (host allowlist)
- Timeout control
- ReAct-compatible error format
"""
import json
import os
import logging
from typing import Dict, Optional
from urllib.parse import urlparse

import httpx

logger = logging.getLogger(__name__)

# Allowed hosts for security (prevent SSRF attacks)
# In production, only add trusted external APIs here
ALLOWED_HOSTS = {
    "api.openweathermap.org",
    "weatherapi.com",
    "api.github.com",
    "api.twitter.com",
    "api.openai.com",
    "api.anthropic.com",
    "api.minimaxi.com",
    "api.minimax.chat",
}

# Environment flag for strict host checking
ENVIRONMENT = os.getenv("ENVIRONMENT", "development")


class APICallerError(Exception):
    """Custom exception for API caller errors"""
    def __init__(self, message: str, error_code: str = "API_ERROR", status_code: int = None):
        super().__init__(message)
        self.error_code = error_code
        self.status_code = status_code


def api_caller_executor(input_data: dict) -> str:
    """
    Generic API caller executor

    Args:
        input_data: Dict containing:
            - url: str (required) - Full URL to call
            - method: str (optional) - GET, POST, PUT, DELETE, PATCH, default GET
            - headers: dict (optional) - Request headers
            - body: dict (optional) - Request body (JSON)
            - params: dict (optional) - Query parameters
            - timeout: int (optional) - Timeout in seconds, default 30, max 120
            - oauth_token: str (optional) - OAuth Bearer token
            - api_key: str (optional) - API key for header injection

    Returns:
        JSON string with response data
    """
    url = input_data.get("url", "")
    method = input_data.get("method", "GET").upper()
    headers = input_data.get("headers", {})
    body = input_data.get("body")
    params = input_data.get("params", {})
    timeout = min(input_data.get("timeout", 30), 120)

    # OAuth/API key injection
    oauth_token = input_data.get("oauth_token") or os.getenv("DEFAULT_API_TOKEN")
    api_key = input_data.get("api_key") or os.getenv("DEFAULT_API_KEY")

    if oauth_token and "Authorization" not in headers:
        headers["Authorization"] = f"Bearer {oauth_token}"
    elif api_key and "Authorization" not in headers:
        headers["X-API-Key"] = api_key

    # Validate URL
    if not url:
        return json.dumps({
            "status": "error",
            "error_code": "INVALID_PARAMETER",
            "message": "url is required"
        }, ensure_ascii=False)

    # Validate URL scheme
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https"):
        return json.dumps({
            "status": "error",
            "error_code": "INVALID_URL",
            "message": "Only HTTP and HTTPS URLs are allowed"
        }, ensure_ascii=False)

    # SSRF protection: validate host against allowlist (all environments)
    if parsed.hostname and parsed.hostname not in ALLOWED_HOSTS:
        return json.dumps({
            "status": "error",
            "error_code": "HOST_NOT_ALLOWED",
            "message": f"Host '{parsed.hostname}' is not in the allowed list for security reasons"
        }, ensure_ascii=False)

    # Validate method
    allowed_methods = {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"}
    if method not in allowed_methods:
        return json.dumps({
            "status": "error",
            "error_code": "INVALID_METHOD",
            "message": f"Method '{method}' not allowed. Use: {', '.join(allowed_methods)}"
        }, ensure_ascii=False)

    try:
        start_time = __import__("time").time()

        with httpx.Client(timeout=timeout) as client:
            request_kwargs = {
                "url": url,
                "headers": headers,
            }

            # Add query params if present
            if params:
                request_kwargs["params"] = params

            # Add body for appropriate methods
            if method in ("POST", "PUT", "PATCH") and body:
                request_kwargs["json"] = body

            # Make request
            response = client.request(method, **request_kwargs)

            elapsed_ms = int((__import__("time").time() - start_time) * 1000)

            # Try to parse JSON response
            try:
                response_data = response.json()
                response_body = None
            except json.JSONDecodeError:
                response_data = None
                response_body = response.text[:10000]  # Limit text response

            result = {
                "status": "success",
                "status_code": response.status_code,
                "elapsed_ms": elapsed_ms,
                "headers": dict(response.headers),
            }

            if response_data is not None:
                result["body"] = response_data
            elif response_body:
                result["body"] = response_body
                result["body_type"] = "text"

            # Check for common error status codes
            if response.status_code >= 400:
                result["status"] = "error"
                if response.status_code == 401:
                    result["error_code"] = "AUTH_FAILED"
                elif response.status_code == 403:
                    result["error_code"] = "FORBIDDEN"
                elif response.status_code == 404:
                    result["error_code"] = "NOT_FOUND"
                elif response.status_code == 429:
                    result["error_code"] = "RATE_LIMITED"
                elif response.status_code >= 500:
                    result["error_code"] = "SERVER_ERROR"

            return json.dumps(result, ensure_ascii=False, default=str)

    except httpx.TimeoutException:
        return json.dumps({
            "status": "error",
            "error_code": "TIMEOUT",
            "message": f"Request timed out after {timeout} seconds"
        }, ensure_ascii=False)
    except httpx.ConnectError as e:
        return json.dumps({
            "status": "error",
            "error_code": "CONNECTION_ERROR",
            "message": f"Failed to connect: {str(e)}"
        }, ensure_ascii=False)
    except httpx.RequestError as e:
        logger.error(f"API request failed: {e}")
        return json.dumps({
            "status": "error",
            "error_code": "REQUEST_ERROR",
            "message": f"Request failed: {str(e)}"
        }, ensure_ascii=False)
    except Exception as e:
        logger.error(f"Unexpected API error: {e}")
        return json.dumps({
            "status": "error",
            "error_code": "INTERNAL_ERROR",
            "message": f"Unexpected error: {str(e)}"
        }, ensure_ascii=False)


def check_host_allowed(hostname: str) -> bool:
    """Check if a hostname is allowed for API calls (all environments)"""
    return hostname in ALLOWED_HOSTS


def add_allowed_host(hostname: str) -> None:
    """Add a hostname to the allowed list (for runtime configuration)"""
    if ENVIRONMENT == "production":
        logger.warning(f"Blocked attempt to modify ALLOWED_HOSTS in production: {hostname}")
        return
    ALLOWED_HOSTS.add(hostname)
    logger.info(f"Added allowed host: {hostname}")
