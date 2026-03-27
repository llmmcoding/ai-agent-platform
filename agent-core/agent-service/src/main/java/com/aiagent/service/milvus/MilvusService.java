package com.aiagent.service.milvus;

import io.milvus.client.MilvusClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.SearchResults;
import io.milvus.param.*;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.QueryResultsWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Milvus 向量检索服务
 * 负责向量搜索、写入、删除等操作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusService {

    private final MilvusClient milvusClient;

    @Value("${aiagent.milvus.collection.default:default}")
    private String defaultCollection;

    @Value("${aiagent.milvus.embedding-dim:1536}")
    private int embeddingDim;

    @Value("${aiagent.milvus.search.top-k:20}")
    private int defaultTopK;

    /**
     * 搜索向量
     *
     * @param queryEmbedding 查询向量
     * @param collection     集合名称
     * @param topK          返回数量
     * @return 搜索结果列表
     */
    public List<Map<String, Object>> searchVectors(List<Float> queryEmbedding, String collection, int topK) {
        try {
            MilvusClient client = milvusClient.getClient();

            // 确保 Collection 存在
            if (!collectionExists(collection)) {
                log.warn("Collection not found: {}", collection);
                return Collections.emptyList();
            }

            // 搜索参数
            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(collection)
                    .withVectors(Collections.singletonList(queryEmbedding))
                    .withVectorFieldName("embedding")
                    .withTopK(topK)
                    .withConsistencyLevel(CONSISTENCY_LEVEL_JINNI)
                    .withParams("{\"nprobe\": 10}")
                    .build();

            // 执行搜索
            SearchResults results = client.search(searchParam);

            // 解析结果
            List<Map<String, Object>> formattedResults = new ArrayList<>();
            QueryResultsWrapper wrapper = new QueryResultsWrapper(results);
            List<QueryResultsWrapper.ScoredVector> scoredVectors = wrapper.getScoredVectors();

            for (QueryResultsWrapper.ScoredVector scoredVector : scoredVectors) {
                Map<String, Object> result = new HashMap<>();
                result.put("id", scoredVector.getID());
                result.put("score", scoredVector.getScore());
                result.put("vector", scoredVector.getVector());

                // 获取标量字段
                Map<String, Object> fields = scoredVector.getFields();
                if (fields.containsKey("content")) {
                    result.put("content", fields.get("content"));
                }
                if (fields.containsKey("metadata")) {
                    result.put("metadata", fields.get("metadata"));
                }

                formattedResults.add(result);
            }

            log.debug("Milvus search returned {} results from collection {}", formattedResults.size(), collection);
            return formattedResults;

        } catch (Exception e) {
            log.error("Milvus search failed: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 批量插入向量
     *
     * @param collection  集合名称
     * @param documents   文档列表，每项包含 content, metadata
     * @param embeddings  对应的 embedding 向量列表
     * @return 插入的文档数量
     */
    public int insertVectors(String collection, List<Map<String, Object>> documents, List<List<Float>> embeddings) {
        try {
            MilvusClient client = milvusClient.getClient();

            // 确保 Collection 存在
            if (!collectionExists(collection)) {
                createCollection(collection);
            }

            // 准备字段数据
            List<List<Float>> vectors = new ArrayList<>();
            List<String> contents = new ArrayList<>();
            List<String> metadatas = new ArrayList<>();

            for (int i = 0; i < documents.size(); i++) {
                Map<String, Object> doc = documents.get(i);
                vectors.add(embeddings.get(i));
                contents.add((String) doc.getOrDefault("content", ""));
                metadatas.add(doc.getOrDefault("metadata", "{}").toString());
            }

            // 构建插入参数
            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(collection)
                    .withFields("embedding", vectors)
                    .withFields("content", contents)
                    .withFields("metadata", metadatas)
                    .build();

            // 执行插入
            client.insert(insertParam);
            client.flush(collection);

            log.info("Inserted {} vectors into collection {}", documents.size(), collection);
            return documents.size();

        } catch (Exception e) {
            log.error("Milvus insert failed: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to insert vectors", e);
        }
    }

    /**
     * 删除向量
     *
     * @param collection 集合名称
     * @param ids        要删除的向量 ID 列表
     * @return 是否成功
     */
    public boolean deleteVectors(String collection, List<String> ids) {
        try {
            MilvusClient client = milvusClient.getClient();

            // 构建删除表达式
            StringBuilder expr = new StringBuilder("id in [");
            for (int i = 0; i < ids.size(); i++) {
                expr.append("\"").append(ids.get(i)).append("\"");
                if (i < ids.size() - 1) {
                    expr.append(",");
                }
            }
            expr.append("]");

            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(collection)
                    .withExpr(expr.toString())
                    .build();

            client.delete(deleteParam);
            client.flush(collection);

            log.info("Deleted {} vectors from collection {}", ids.size(), collection);
            return true;

        } catch (Exception e) {
            log.error("Milvus delete failed: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 检查 Collection 是否存在
     */
    public boolean collectionExists(String collection) {
        try {
            MilvusClient client = milvusClient.getClient();
            return client.hasCollection(collection);
        } catch (Exception e) {
            log.error("Failed to check collection existence: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 创建 Collection
     */
    public void createCollection(String collection) {
        try {
            MilvusClient client = milvusClient.getClient();

            // 定义字段
            FieldType embeddingField = FieldType.newBuilder()
                    .withName("embedding")
                    .withDataType(io.milvus.grpc.DataType.FloatVector)
                    .withDimension(embeddingDim)
                    .build();

            FieldType contentField = FieldType.newBuilder()
                    .withName("content")
                    .withDataType(io.milvus.grpc.DataType.VarChar)
                    .withMaxLength(65535)
                    .build();

            FieldType metadataField = FieldType.newBuilder()
                    .withName("metadata")
                    .withDataType(io.milvus.grpc.DataType.JSON)
                    .build();

            // 构建创建参数
            CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                    .withCollectionName(collection)
                    .withDescription("AI Agent RAG collection: " + collection)
                    .withFieldTypes(embeddingField, contentField, metadataField)
                    .build();

            client.createCollection(createParam);

            // 创建索引
            client.createIndex(IndexParam.newBuilder()
                    .withCollectionName(collection)
                    .withFieldName("embedding")
                    .withIndexType(IndexType.IVF_FLAT)
                    .withMetricType(MetricType.IP)
                    .withParams("{\"nlist\": 128}")
                    .build());

            log.info("Created Milvus collection: {}", collection);

        } catch (Exception e) {
            log.error("Failed to create collection {}: {}", collection, e.getMessage(), e);
            throw new RuntimeException("Failed to create collection", e);
        }
    }

    /**
     * 获取默认 Collection 名称
     */
    public String getDefaultCollection() {
        return defaultCollection;
    }
}
