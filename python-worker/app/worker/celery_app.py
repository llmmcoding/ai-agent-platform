"""
Celery 应用配置
"""
from celery import Celery
from app.config import settings

celery_app = Celery(
    "ai_agent_worker",
    broker=settings.CELERY_BROKER_URL,
    backend=settings.CELERY_RESULT_BACKEND,
    include=["app.tasks.tool_tasks", "app.tasks.rag_tasks"]
)

# Celery 配置
celery_app.conf.update(
    task_serializer="json",
    accept_content=["json"],
    result_serializer="json",
    timezone="Asia/Shanghai",
    enable_utc=True,
    task_track_started=True,
    task_time_limit=300,  # 5分钟超时
    task_soft_time_limit=240,  # 4分钟软超时
    worker_prefetch_multiplier=4,
    worker_max_tasks_per_child=1000,
)
