"""
健康检查路由
"""
from fastapi import APIRouter

router = APIRouter()


@router.get("/health")
async def health_check():
    """健康检查"""
    return {
        "status": "healthy",
        "service": "ai-agent-python-worker",
        "version": "1.0.0"
    }


@router.get("/ready")
async def readiness_check():
    """就绪检查"""
    # TODO: 检查 Kafka、Redis、Milvus 连接
    return {"status": "ready"}
