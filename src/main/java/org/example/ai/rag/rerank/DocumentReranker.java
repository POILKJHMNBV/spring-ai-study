package org.example.ai.rag.rerank;

import org.example.ai.rag.RetrievedChunk;

import java.util.List;

/**
 * 只重排已召回的候选，不负责向量检索、来源校验或向量阈值过滤。
 * 实现必须保留每个候选的原始向量 score，且不得修改输入列表。
 */
public interface DocumentReranker {
    /**
     * 根据 query 对候选重新排序并截取前 topK 个。候选同分时保持传入顺序。
     */
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK);
}
