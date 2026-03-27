"""
应用配置
"""
import os
from pydantic_settings import BaseSettings
from functools import lru_cache


class Settings(BaseSettings):
    """应用配置"""

    # Kafka 配置
    KAFKA_BOOTSTRAP_SERVERS: str = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
    KAFKA_CONSUMER_GROUP: str = os.getenv("KAFKA_CONSUMER_GROUP", "ai-agent-python-worker")

    # Redis 配置
    REDIS_HOST: str = os.getenv("REDIS_HOST", "localhost")
    REDIS_PORT: int = int(os.getenv("REDIS_PORT", "6379"))
    REDIS_PASSWORD: str = os.getenv("REDIS_PASSWORD", "")
    REDIS_DB: int = int(os.getenv("REDIS_DB", "0"))

    # LLM 配置
    OPENAI_API_KEY: str = os.getenv("OPENAI_API_KEY", "")
    OPENAI_BASE_URL: str = os.getenv("OPENAI_BASE_URL", "https://api.openai.com")
    ANTHROPIC_API_KEY: str = os.getenv("ANTHROPIC_API_KEY", "")

    # Embedding 配置
    EMBEDDING_PROVIDER: str = os.getenv("EMBEDDING_PROVIDER", "openai")  # openai | local
    OPENAI_EMBEDDING_MODEL: str = os.getenv("OPENAI_EMBEDDING_MODEL", "text-embedding-3-small")
    LOCAL_EMBEDDING_MODEL: str = os.getenv("LOCAL_EMBEDDING_MODEL", "sentence-transformers/all-MiniLM-L6-v2")
    EMBEDDING_DIM: int = 1536  # OpenAI text-embedding-3-small dim=1536, MiniLM dim=384

    # Rerank 配置
    RERANK_PROVIDER: str = os.getenv("RERANK_PROVIDER", "none")  # none | cohere | bge | cross_encoder
    COHERE_API_KEY: str = os.getenv("COHERE_API_KEY", "")
    COHERE_RERANK_MODEL: str = os.getenv("COHERE_RERANK_MODEL", "rerank-english-v2.0")
    BGE_RERANK_MODEL: str = os.getenv("BGE_RERANK_MODEL", "BAAI/bge-reranker-base")
    CROSS_ENCODER_MODEL: str = os.getenv("CROSS_ENCODER_MODEL", "cross-encoder/ms-marco-MiniLM-L-6-v2")
    RERANK_TOP_K: int = int(os.getenv("RERANK_TOP_K", "20"))  # 初筛数量
    RERANK_FINAL_K: int = int(os.getenv("RERANK_FINAL_K", "5"))  # 最终返回

    # Java 向量检索服务地址
    VECTOR_SEARCH_URL: str = os.getenv("VECTOR_SEARCH_URL", "http://localhost:8080/api/v1/rag/search")

    # Celery 配置
    CELERY_BROKER_URL: str = os.getenv("CELERY_BROKER_URL", "redis://localhost:6379/1")
    CELERY_RESULT_BACKEND: str = os.getenv("CELERY_RESULT_BACKEND", "redis://localhost:6379/2")

    # 日志配置
    LOG_LEVEL: str = os.getenv("LOG_LEVEL", "INFO")

    class Config:
        env_file = ".env"
        case_sensitive = True


@lru_cache()
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
