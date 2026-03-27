package com.aiagent.service.milvus;

import io.milvus.client.MilvusClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.*;
import io.milvus.param.collection.*;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.response.SearchResultsWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Milvus 向量检索服务 (SDK 2.x)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusService {

    private final MilvusConnectionManager connectionManager;

    @Value("${aiagent.milvus.collection.default:default}")
    private String defaultCollection;

    @Value("${aiagent.milvus.embedding-dim:1536}")
    private int embeddingDim;

    @Value("${aiagent.milvus.search.top-k:20}")
    private int defaultTopK;

    /**
     * 搜索向量
     */
    public List<Map<String, Object>> searchVectors(List<Float> queryEmbedding, String collection, int topK) {
        try {
            MilvusClient client = connectionManager.getClient();
            if (client == null) {
                log.error("Milvus client is not initialized");
                return Collections.emptyList();
            }

            if (!collectionExists(collection)) {
                log.warn("Collection not found: {}", collection);
                return Collections.emptyList();
            }

            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(collection)
                    .withVectors(Collections.singletonList(queryEmbedding))
                    .withVectorFieldName("embedding")
                    .withTopK(topK)
                    .withConsistencyLevel(ConsistencyLevelEnum.EVENTUALLY)
                    .withParams("{\"nprobe\": 10}")
                    .build();

            R<SearchResults> response = client.search(searchParam);

            if (response.getStatus() != R.Status.Success.getCode()) {
                log.error("Milvus search failed: {}", response.getMessage());
                return Collections.emptyList();
            }

            SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
            List<SearchResultsWrapper.IDScore> idScores = wrapper.getIDScore(0);

            List<Map<String, Object>> formattedResults = new ArrayList<>();
            for (SearchResultsWrapper.IDScore idScore : idScores) {
                Map<String, Object> map = new HashMap<>();
                map.put("id", idScore.getStrID());
                map.put("score", idScore.getScore());
                map.put("fieldValues", idScore.getFieldValues());
                formattedResults.add(map);
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
     */
    public int insertVectors(String collection, List<Map<String, Object>> documents, List<List<Float>> embeddings) {
        try {
            MilvusClient client = connectionManager.getClient();
            if (client == null) {
                throw new RuntimeException("Milvus client is not initialized");
            }

            if (!collectionExists(collection)) {
                createCollection(collection);
            }

            List<String> ids = new ArrayList<>();
            List<List<Float>> vectors = new ArrayList<>();
            List<String> contents = new ArrayList<>();
            List<String> metadatas = new ArrayList<>();

            for (int i = 0; i < documents.size(); i++) {
                Map<String, Object> doc = documents.get(i);
                ids.add(doc.getOrDefault("id", UUID.randomUUID().toString()).toString());
                vectors.add(embeddings.get(i));
                contents.add((String) doc.getOrDefault("content", ""));
                metadatas.add(doc.getOrDefault("metadata", "{}").toString());
            }

            // 构建字段
            List<InsertParam.Field> fields = new ArrayList<>();
            fields.add(new InsertParam.Field("id", ids));
            fields.add(new InsertParam.Field("embedding", vectors));
            fields.add(new InsertParam.Field("content", contents));
            fields.add(new InsertParam.Field("metadata", metadatas));

            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(collection)
                    .withFields(fields)
                    .build();

            R<MutationResult> response = client.insert(insertParam);

            if (response.getStatus() != R.Status.Success.getCode()) {
                log.error("Milvus insert failed: {}", response.getMessage());
                throw new RuntimeException("Failed to insert vectors");
            }

            // Flush
            FlushParam flushParam = FlushParam.newBuilder()
                    .withCollectionNames(Collections.singletonList(collection))
                    .build();
            client.flush(flushParam);

            log.info("Inserted {} vectors into collection {}", documents.size(), collection);
            return documents.size();

        } catch (Exception e) {
            log.error("Milvus insert failed: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to insert vectors", e);
        }
    }

    /**
     * 删除向量
     */
    public boolean deleteVectors(String collection, List<String> ids) {
        try {
            MilvusClient client = connectionManager.getClient();
            if (client == null) {
                log.error("Milvus client is not initialized");
                return false;
            }

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

            R<MutationResult> response = client.delete(deleteParam);

            if (response.getStatus() != R.Status.Success.getCode()) {
                log.error("Milvus delete failed: {}", response.getMessage());
                return false;
            }

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
            MilvusClient client = connectionManager.getClient();
            if (client == null) {
                return false;
            }
            HasCollectionParam hasParam = HasCollectionParam.newBuilder()
                    .withCollectionName(collection)
                    .build();
            R<Boolean> response = client.hasCollection(hasParam);
            return response.getStatus() == R.Status.Success.getCode() && response.getData();
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
            MilvusClient client = connectionManager.getClient();
            if (client == null) {
                throw new RuntimeException("Milvus client is not initialized");
            }

            FieldType idField = FieldType.newBuilder()
                    .withName("id")
                    .withDataType(io.milvus.grpc.DataType.VarChar)
                    .withMaxLength(128)
                    .build();

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
                    .withDataType(io.milvus.grpc.DataType.VarChar)
                    .withMaxLength(65535)
                    .build();

            List<FieldType> fieldTypes = Arrays.asList(idField, embeddingField, contentField, metadataField);

            CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                    .withCollectionName(collection)
                    .withDescription("AI Agent RAG collection: " + collection)
                    .withFieldTypes(fieldTypes)
                    .build();

            client.createCollection(createParam);

            // Create index
            CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                    .withCollectionName(collection)
                    .withFieldName("embedding")
                    .withIndexType(IndexType.IVF_FLAT)
                    .withMetricType(MetricType.IP)
                    .withExtraParam("{\"nlist\": 128}")
                    .build();
            client.createIndex(indexParam);

            // Load collection
            LoadCollectionParam loadParam = LoadCollectionParam.newBuilder()
                    .withCollectionName(collection)
                    .build();
            client.loadCollection(loadParam);

            log.info("Created Milvus collection: {}", collection);

        } catch (Exception e) {
            log.error("Failed to create collection {}: {}", collection, e.getMessage(), e);
            throw new RuntimeException("Failed to create collection", e);
        }
    }

    public String getDefaultCollection() {
        return defaultCollection;
    }
}
