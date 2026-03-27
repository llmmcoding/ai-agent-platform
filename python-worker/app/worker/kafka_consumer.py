"""
Kafka 消费者
用于接收 Java Agent Core 的任务
"""
import asyncio
import json
import logging
from typing import Callable, Dict, Any

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
from aiokafka.errors import KafkaError

from app.config import settings

logger = logging.getLogger(__name__)


class KafkaWorker:
    """Kafka Worker"""

    def __init__(self):
        self.consumer = None
        self.producer = None
        self.running = False
        self.handlers: Dict[str, Callable] = {}

    def register_handler(self, topic: str, handler: Callable):
        """注册消息处理器"""
        self.handlers[topic] = handler
        logger.info(f"Registered handler for topic: {topic}")

    async def start(self):
        """启动 Kafka Consumer"""
        self.consumer = AIOKafkaConsumer(
            bootstrap_servers=settings.KAFKA_BOOTSTRAP_SERVERS,
            group_id=settings.KAFKA_CONSUMER_GROUP,
            value_deserializer=lambda m: json.loads(m.decode('utf-8')),
            auto_offset_reset='earliest',
            enable_auto_commit=False
        )

        self.producer = AIOKafkaProducer(
            bootstrap_servers=settings.KAFKA_BOOTSTRAP_SERVERS,
            value_serializer=lambda v: json.dumps(v).encode('utf-8')
        )

        await self.consumer.start()
        await self.producer.start()

        self.running = True
        logger.info("Kafka consumer started")

        # 订阅主题
        topics = list(self.handlers.keys())
        await self.consumer.subscribe(topics)
        logger.info(f"Subscribed to topics: {topics}")

    async def stop(self):
        """停止 Kafka Consumer"""
        self.running = False
        if self.consumer:
            await self.consumer.stop()
        if self.producer:
            await self.producer.stop()
        logger.info("Kafka consumer stopped")

    async def run(self):
        """运行消费者循环"""
        try:
            async for message in self.consumer:
                if not self.running:
                    break

                topic = message.topic
                value = message.value

                logger.debug(f"Received message from {topic}: {value}")

                handler = self.handlers.get(topic)
                if handler:
                    try:
                        await handler(value)
                        await self.consumer.commit()
                    except Exception as e:
                        logger.error(f"Handler error for topic {topic}", exc_info=e)
                else:
                    logger.warning(f"No handler for topic: {topic}")

        except KafkaError as e:
            logger.error(f"Kafka error", exc_info=e)


# 全局实例
kafka_worker = KafkaWorker()


# ==================== 消息处理器 ====================

async def handle_tool_execute(message: dict):
    """处理工具执行消息"""
    tool_name = message.get("tool_name")
    input_data = message.get("input", {})
    trace_id = message.get("trace_id", "")

    logger.info(f"Processing tool execute: {tool_name}, trace_id: {trace_id}")

    try:
        from app.tools.registry import tool_registry

        executor = tool_registry.get_executor(tool_name)
        if not executor:
            result = {"success": False, "error": f"Tool not found: {tool_name}"}
        else:
            if asyncio.iscoroutinefunction(executor):
                exec_result = await executor(input_data)
            else:
                exec_result = executor(input_data)
            result = {"success": True, "result": str(exec_result)}

    except Exception as e:
        logger.error(f"Tool execution failed: {tool_name}", exc_info=e)
        result = {"success": False, "error": str(e)}

    # 发送结果到结果主题
    await send_tool_result(trace_id, tool_name, result)


async def send_tool_result(trace_id: str, tool_name: str, result: dict):
    """发送工具执行结果"""
    if not kafka_worker.producer:
        return

    message = {
        "trace_id": trace_id,
        "tool_name": tool_name,
        "result": result
    }

    try:
        await kafka_worker.producer.send_and_wait("ai-agent-tool-result", message)
        logger.debug(f"Tool result sent: {tool_name}")
    except Exception as e:
        logger.error(f"Failed to send tool result", exc_info=e)


async def handle_rag_query(message: dict):
    """处理 RAG 查询消息"""
    query = message.get("query")
    collection = message.get("collection", "default")
    user_id = message.get("user_id", "")

    logger.info(f"Processing RAG query: {query[:50]}..., collection: {collection}")

    try:
        from app.rag.retriever import RAGRetriever

        retriever = RAGRetriever()
        results = await retriever.query(query=query, collection=collection, top_k=5)

        result = {"success": True, "results": results}

    except Exception as e:
        logger.error("RAG query failed", exc_info=e)
        result = {"success": False, "error": str(e)}

    # 发送结果到结果主题
    await send_rag_result(user_id, result)


async def send_rag_result(user_id: str, result: dict):
    """发送 RAG 查询结果"""
    if not kafka_worker.producer:
        return

    message = {
        "user_id": user_id,
        "result": result
    }

    try:
        await kafka_worker.producer.send_and_wait("ai-agent-rag-result", message)
        logger.debug("RAG result sent")
    except Exception as e:
        logger.error("Failed to send RAG result", exc_info=e)


async def init_kafka_worker():
    """初始化 Kafka Worker"""
    # 注册处理器
    kafka_worker.register_handler("ai-agent-tool-execute", handle_tool_execute)
    kafka_worker.register_handler("ai-agent-rag-query", handle_rag_query)

    # 启动
    await kafka_worker.start()


async def run_kafka_worker():
    """运行 Kafka Worker"""
    await kafka_worker.run()
