package org.example.ai.rag;

/**
 * 检索证据及来源，用于构建 LLM 上下文和调试检索质量。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code source} — 知识文件名（如 {@code kafka-consumer-lag.md}）</li>
 *   <li>{@code title} — 文档一级标题</li>
 *   <li>{@code domain} — 领域标签（thread-pool / kafka / mysql）</li>
 *   <li>{@code section} — 文档二级标题，定位到具体小节</li>
 *   <li>{@code chunkId} — 块的 SHA-256 ID，内容不变则 ID 不变</li>
 *   <li>{@code chunkIndex} — 块在文档内的顺序索引，用于判断上下文连续性</li>
 *   <li>{@code score} — 原始向量相似度，不是置信概率；融合排序后不一定按 score 递减</li>
 *   <li>{@code text} — 块的完整正文（含标题前缀）</li>
 * </ul>
 * </p>
 */
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
