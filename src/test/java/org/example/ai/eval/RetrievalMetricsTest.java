package org.example.ai.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 不连接数据库的指标契约，专门保护 chunk 名次与 source 去重的分界。 */
class RetrievalMetricsTest {
    /** 同源三个 chunk 占满前三名时，多来源召回只能算命中一个。 */
    @Test
    void duplicateSourceWithinThreeChunksDoesNotInflateRecall() {
        var score = RetrievalMetrics.score(Set.of("mysql", "kafka"),
                List.of("mysql", "mysql", "mysql", "kafka"));
        assertEquals(0.5, score.recallAt1());
        assertEquals(0.5, score.recallAt3());
        assertEquals(1.0, score.mrrAt20());
        assertEquals(1, score.firstRelevantRank());
    }

    /** MRR 保留原始 chunk 位次，超过 20 名视为未召回。 */
    @Test
    void reciprocalRankUsesChunkPositionAndStopsAtTwenty() {
        var third = RetrievalMetrics.score(Set.of("mysql"), List.of("kafka", "thread", "mysql"));
        assertEquals(0, third.recallAt1());
        assertEquals(1, third.recallAt3());
        assertEquals(1.0 / 3, third.mrrAt20(), 1e-12);
        var beyond = new java.util.ArrayList<>(java.util.Collections.nCopies(20, "kafka"));
        beyond.add("mysql");
        assertEquals(0, RetrievalMetrics.score(Set.of("mysql"), beyond).mrrAt20());
        assertEquals(0, RetrievalMetrics.score(Set.of("mysql"), List.of()).mrrAt20());
    }

    /** 固定集至少有四个计划问法，开发/验证两组均存在。 */
    @Test
    void datasetKeepsPlansAndSourcesAcrossIndependentSplits() throws Exception {
        var cases = RetrievalCases.load();
        assertEquals(18, cases.size());
        assertEquals(4, cases.stream().filter(c -> c.kind().equals("plan")).count());
        assertTrue(cases.stream().anyMatch(c -> c.expectedSources().size() > 1));
    }

    /** 空标签会让召回分母失效，必须拒绝。 */
    @Test
    void missingGoldIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RetrievalMetrics.score(Set.of(), List.of("mysql")));
    }
}
