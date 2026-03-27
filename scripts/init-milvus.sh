#!/bin/bash
# Milvus 初始化脚本

set -e

MILVUS_HOST=${MILVUS_HOST:-localhost}
MILVUS_PORT=${MILVUS_PORT:-19530}

echo "Connecting to Milvus at ${MILVUS_HOST}:${MILVUS_PORT}..."

# 等待 Milvus 就绪
until docker run --rm milvusdb/milvus:latest curl -s "http://${MILVUS_HOST}:${MILVUS_PORT}/health" > /dev/null 2>&1; do
    echo "Waiting for Milvus to be ready..."
    sleep 5
done

echo "Milvus is ready!"

# 创建默认 Collection（如果不存在）
docker run --rm -e MILVUS_HOST=${MILVUS_HOST} -e MILVUS_PORT=${MILVUS_PORT} \
    milvusdb/milvus:latest python scripts/create_collection.py
