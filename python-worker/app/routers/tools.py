"""
工具执行路由
"""
import logging
from typing import Dict, Any

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from app.tasks.tool_tasks import execute_tool_task

logger = logging.getLogger(__name__)
router = APIRouter()


class ToolExecuteRequest(BaseModel):
    tool_name: str
    input: Dict[str, Any]
    trace_id: str = ""


class ToolExecuteResponse(BaseModel):
    success: bool
    result: str = ""
    error: str = ""
    duration_ms: int = 0


@router.post("/execute", response_model=ToolExecuteResponse)
async def execute_tool(request: ToolExecuteRequest) -> ToolExecuteResponse:
    """同步执行工具"""
    import time
    start_time = time.time()

    try:
        logger.info(f"Executing tool: {request.tool_name}, trace_id: {request.trace_id}")

        result = execute_tool_task(request.tool_name, request.input)

        duration_ms = int((time.time() - start_time) * 1000)

        return ToolExecuteResponse(
            success=True,
            result=result,
            duration_ms=duration_ms
        )
    except Exception as e:
        logger.error(f"Tool execution failed: {request.tool_name}, error: {e}")
        duration_ms = int((time.time() - start_time) * 1000)

        return ToolExecuteResponse(
            success=False,
            error=str(e),
            duration_ms=duration_ms
        )


@router.post("/execute/async")
async def execute_tool_async(request: ToolExecuteRequest):
    """异步执行工具"""
    task = execute_tool_task.delay(request.tool_name, request.input)

    return {
        "task_id": task.id,
        "status": "PENDING"
    }
