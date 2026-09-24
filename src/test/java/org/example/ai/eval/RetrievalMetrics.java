package org.example.ai.eval;

import org.example.ai.rag.RetrievedChunk;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 按预先固定的 source 标签评估；一个 source 的多个 chunk 仅代表一个相关文档。 */
final class RetrievalMetrics {
    /** 纯指标函数，不创建实例。 */
    private RetrievalMetrics() {
    }

    /** 单例 Recall 与首个相关 chunk 的倒数排名。 */
    record Score(double recallAt1, double recallAt3, double mrrAt20, int firstRelevantRank) {
    }

    /**
     * Recall@K 先保留前 K 个 chunk，再对 source 去重，分母为 gold source 数。
     * MRR@20 取前 20 个 chunk 中首个相关 source 的原始 chunk 名次；未命中记零。
     * 如一来源占据前 3 个 chunk，多来源查询的 Recall@3 最多只能是 1/|gold|。
     */
    static Score score(Set<String> expectedSources, List<String> rankedSources) {
        if (expectedSources == null || expectedSources.isEmpty()
                || expectedSources.stream().anyMatch(s -> s == null || s.isBlank())) {
            throw new IllegalArgumentException("gold source 不得为空");
        }
        if (rankedSources == null || rankedSources.stream().anyMatch(s -> s == null || s.isBlank())) {
            throw new IllegalArgumentException("排名 source 不得为空");
        }
        int first = 0;
        for (int i = 0; i < Math.min(20, rankedSources.size()); i++) {
            if (expectedSources.contains(rankedSources.get(i))) {
                first = i + 1;
                break;
            }
        }
        return new Score(recall(expectedSources, rankedSources, 1),
                recall(expectedSources, rankedSources, 3), first == 0 ? 0 : 1.0 / first, first);
    }

    /** 测试入口统一从真实检索 DTO 读取 source，不读取 chunkId 或可变的标题。 */
    static Score scoreChunks(Set<String> expectedSources, List<RetrievedChunk> chunks) {
        return score(expectedSources, chunks.stream().map(RetrievedChunk::source).toList());
    }

    /** 仅在前 k 个 chunk 内按 source 去重，避免先去重后截断。 */
    private static double recall(Set<String> gold, List<String> ranked, int k) {
        Set<String> found = new HashSet<>(ranked.subList(0, Math.min(k, ranked.size())));
        found.retainAll(gold);
        return (double) found.size() / gold.size();
    }
}
