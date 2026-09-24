package org.example.ai.rag;

/**
 * 检索排序模式。三个模式共用一次向量候选检索及同一向量阈值，仅排序策略不同。
 *
 * <p>用于 A/B/C 对照实验，比较纯向量、混合排序和重排后的检索质量。
 * 配置项 {@code rag.retrieval.mode} 决定生产环境使用的模式。</p>
 */
public enum RetrievalMode {
    /**
     * 纯向量模式：直接采用向量库返回的相似度顺序。
     * 最简单，适合语义明确且单领域的问题。
     */
    VECTOR,
    /**
     * 混合模式：在向量候选上使用词法 BM25 与向量相似度进行 RRF（Reciprocal Rank Fusion）融合排序。
     * 兼顾语义相似和关键词匹配，适合包含专业术语的问题。
     */
    HYBRID,
    /**
     * 混合重排模式：在 HYBRID 顺序上再用独立的确定性重排器（如 TokenCoverageReranker）排序。
     * 重排器会考虑查询词覆盖率，进一步提升包含多个关键词的问题的检索质量。
     */
    HYBRID_RERANK
}
