"""
AI Agent Python Worker
提供 Tool 执行和 RAG 能力
"""

import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from app.routers import tools, rag, health, embedding
from app.worker.celery_app import celery_app
from app.config import settings

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期管理"""
    logger.info("Starting AI Agent Python Worker...")
    logger.info(f"Kafka bootstrap servers: {settings.KAFKA_BOOTSTRAP_SERVERS}")
    logger.info(f"Redis: {settings.REDIS_HOST}:{settings.REDIS_PORT}")
    yield
    logger.info("Shutting down AI Agent Python Worker...")


app = FastAPI(
    title="AI Agent Python Worker",
    description="提供 Tool 执行和 RAG 能力的 Python Worker",
    version="1.0.0",
    lifespan=lifespan
)

# 注册路由
app.include_router(health.router, prefix="/api/v1", tags=["健康检查"])
app.include_router(tools.router, prefix="/api/v1/tools", tags=["工具执行"])
app.include_router(rag.router, prefix="/api/v1/rag", tags=["RAG检索"])
app.include_router(embedding.router, prefix="/api/v1", tags=["Embedding"])


class TaskSubmitRequest(BaseModel):
    task_type: str  # "tool_execute" | "rag_query"
    task_data: dict


class TaskSubmitResponse(BaseModel):
    task_id: str
    status: str


@app.post("/api/v1/tasks", response_model=TaskSubmitResponse)
async def submit_task(request: TaskSubmitRequest):
    """提交异步任务"""
    try:
        if request.task_type == "tool_execute":
            result = celery_app.send_task(
                "tasks.execute_tool",
                args=[request.task_data]
            )
        elif request.task_type == "rag_query":
            result = celery_app.send_task(
                "tasks.rag_query",
                args=[request.task_data]
            )
        else:
            raise HTTPException(status_code=400, detail=f"Unknown task type: {request.task_type}")

        return TaskSubmitResponse(
            task_id=result.id,
            status="PENDING"
        )
    except Exception as e:
        logger.error(f"Failed to submit task: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/v1/tasks/{task_id}")
async def get_task_status(task_id: str):
    """查询任务状态"""
    result = celery_app.AsyncResult(task_id)
    return {
        "task_id": task_id,
        "status": result.state,
        "result": result.result if result.ready() else None
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=8001,
        reload=True,
        workers=1
    )
