package org.example.ai.rag;

/** 检索证据及来源；score 是原始向量相似度，融合排序后不一定按 score 递减。 */
public record RetrievedChunk(
        String source,
        String title,
        String domain,
        String section,
        String chunkId,
        int chunkIndex,
        Double score,
        String text
) {
}
