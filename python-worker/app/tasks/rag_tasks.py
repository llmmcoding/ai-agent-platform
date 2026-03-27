"""
RAG 任务
"""
import logging
from app.worker.celery_app import celery_app
from app.rag.retriever import RAGRetriever

logger = logging.getLogger(__name__)
rag_retriever = RAGRetriever()


@celery_app.task(name="tasks.rag_query", bind=True)
def rag_query_task(self, query_data: dict):
    """RAG 查询任务"""
    logger.info(f"RAG query task: {self.request.id}")

    try:
        result = rag_retriever.query_sync(
            query=query_data["query"],
            collection=query_data["collection"],
            top_k=query_data.get("top_k", 5)
        )
        return result

    except Exception as e:
        logger.error(f"RAG query failed: {e}")
        raise
