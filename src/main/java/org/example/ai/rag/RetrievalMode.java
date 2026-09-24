package org.example.ai.rag;

/**
 * Day 12 检索对照模式。三个模式共用一次向量候选检索及同一向量阈值。
 */
public enum RetrievalMode {
    /** 直接采用向量库返回的相似度顺序。 */
    VECTOR,
    /** 在向量候选上使用现有的词法 BM25 与 RRF 融合排序。 */
    HYBRID,
    /** 在 HYBRID 顺序上再用独立的确定性重排器排序。 */
    HYBRID_RERANK
}
