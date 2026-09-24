package org.example.ai.rag.rerank;

import org.example.ai.rag.RetrievedChunk;

import java.util.List;

/**
 * 重排器接口：只重排已召回的候选，不负责向量检索、来源校验或向量阈值过滤。
 *
 * <p>设计约束：
 * <ul>
 *   <li>实现必须保留每个候选的原始向量 {@code score}，不得将其改写为重排分或置信概率</li>
 *   <li>不得修改输入列表，返回新的排序结果</li>
 *   <li>候选同分时应保持传入顺序（稳定排序）</li>
 * </ul>
 * </p>
 *
 * <p>典型实现：{@link TokenCoverageReranker} 基于词项覆盖率重排。</p>
 */
public interface DocumentReranker {
    /**
     * 根据 query 对候选重新排序并截取前 topK 个。
     *
     * @param query      用户问题，用于计算重排分数
     * @param candidates 已过滤的候选列表，按 RRF 或其他预排序顺序传入
     * @param topK       期望返回条数
     * @return 重排后的候选列表，不超过 topK 个
     */
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK);
}
