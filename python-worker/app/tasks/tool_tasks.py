"""
Tool 执行任务
"""
import logging
from app.worker.celery_app import celery_app
from app.tools.registry import tool_registry

logger = logging.getLogger(__name__)


@celery_app.task(name="tasks.execute_tool", bind=True)
def execute_tool_task(self, tool_name: str, input_data: dict):
    """执行工具任务"""
    logger.info(f"Executing tool: {tool_name}, task_id: {self.request.id}")

    try:
        executor = tool_registry.get_executor(tool_name)
        if not executor:
            raise ValueError(f"Tool not found: {tool_name}")

        result = executor(input_data)
        logger.info(f"Tool executed successfully: {tool_name}")
        return result

    except Exception as e:
        logger.error(f"Tool execution failed: {tool_name}, error: {e}")
        raise
