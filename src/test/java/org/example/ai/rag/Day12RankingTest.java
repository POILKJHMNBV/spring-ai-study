package org.example.ai.rag;

import org.example.ai.rag.rerank.TokenCoverageReranker;
import org.example.ai.rag.rerank.DocumentReranker;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Day 12 排序链路的无模型回归：同批候选、空正文、阈值和原始向量分。 */
class Day12RankingTest {
    /** 空正文保留候选下标，后续 BM25 匹配仍指向正确文档。 */
    @Test
    void emptyTextCandidateDoesNotShiftBm25DocumentAlignment() {
        var first = document("first", "", 0.9);
        var second = document("second", "无关内容", 0.8);
        var third = document("third", "线程耗尽", 0.7);
        assertEquals(3, new LexicalRrfRanker().rank("线程耗尽", List.of(first, second, third)).size());
        assertEquals("third", new LexicalRrfRanker().rank("线程耗尽", List.of(first, second, third)).get(0).getId());
    }

    /** 同一批候选的三种模式可比较，且评测排序不触发二次检索。 */
    @Test
    void sameCandidatesSupportThreeModesWithoutChangingInputsOrVectorScores() {
        var store = mock(VectorStore.class);
        var retriever = new KnowledgeRetriever(store, new RetrievalProperties(3, 0), new LexicalRrfRanker());
        var first = document("first", "无关文字", 0.9);
        var second = document("second", "线程耗尽，执行器无法接收任务", 0.8);
        var mutable = new ArrayList<>(List.of(first, second));
        var before = List.copyOf(mutable);

        var vector = retriever.rankCandidates("线程耗尽", mutable, RetrievalMode.VECTOR, 2, 0);
        var hybrid = retriever.rankCandidates("线程耗尽", mutable, RetrievalMode.HYBRID, 2, 0);
        var reranked = retriever.rankCandidates("线程耗尽", mutable, RetrievalMode.HYBRID_RERANK, 2, 0);

        assertEquals(List.of("first", "second"), vector.stream().map(RetrievedChunk::chunkId).toList());
        assertEquals("second", hybrid.get(0).chunkId());
        assertEquals("second", reranked.get(0).chunkId());
        assertEquals(0.8, reranked.get(0).score());
        assertEquals(before, mutable);
        verifyNoInteractions(store);
        for (RetrievalMode mode : RetrievalMode.values()) {
            assertEquals(List.of("first"), retriever.rankCandidates("线程耗尽", mutable, mode, 2, 0.85)
                    .stream().map(RetrievedChunk::chunkId).toList());
        }
    }

    /** 重排器同分沿用先前顺序，输入和输出相互独立。 */
    @Test
    void rerankerIsStableAndReturnsFreshList() {
        var reranker = new TokenCoverageReranker();
        var first = new RetrievedChunk("mysql-slow-query.md", "无关", "mysql", "无关", "a", 0, 0.9, "无关");
        var second = new RetrievedChunk("mysql-slow-query.md", "线程耗尽", "mysql", "诊断", "b", 1, 0.8, "线程耗尽");
        var equal = new RetrievedChunk("mysql-slow-query.md", "线程耗尽", "mysql", "诊断", "c", 2, 0.7, "线程耗尽");
        var input = new ArrayList<>(List.of(first, second, equal));
        var result = reranker.rerank("线程耗尽", input, 2);
        assertEquals(List.of("b", "c"), result.stream().map(RetrievedChunk::chunkId).toList());
        assertEquals(List.of(first, second, equal), input);
        assertThrows(UnsupportedOperationException.class, () -> result.add(first));
    }

    /** 向量召回窗口不随外部 topK 或阈值变动。 */
    @Test
    void searchAlwaysUsesFixedTwentyCandidateWindow() {
        var store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document("one", "正文", 0.7)));
        var retriever = new KnowledgeRetriever(store, new RetrievalProperties(3, 0.47), new LexicalRrfRanker());
        retriever.searchCandidates("查询");
        var captor = org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
        verify(store).similaritySearch(captor.capture());
        assertEquals(20, captor.getValue().getTopK());
        assertEquals(0.0, captor.getValue().getSimilarityThreshold());
    }

    /** 配置切换到 C 时，默认生产入口会调用注入的重排器并传递过滤后候选和 topK。 */
    @Test
    void configuredRerankModeUsesInjectedRerankerAfterThreshold() {
        var store = mock(VectorStore.class);
        var reranker = mock(DocumentReranker.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                document("high", "线程耗尽", 0.9), document("low", "线程耗尽", 0.4)));
        when(reranker.rerank(any(), any(), anyInt())).thenAnswer(call -> {
            List<RetrievedChunk> input = call.getArgument(1);
            return List.copyOf(input);
        });
        var retriever = new KnowledgeRetriever(store,
                new RetrievalProperties(2, 0.5, RetrievalMode.HYBRID_RERANK),
                new LexicalRrfRanker(), reranker);

        var actual = retriever.retrieve("线程耗尽");

        assertEquals(List.of("high"), actual.stream().map(RetrievedChunk::chunkId).toList());
        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass((Class<List<RetrievedChunk>>) (Class<?>) List.class);
        verify(reranker).rerank(eq("线程耗尽"), captor.capture(), eq(2));
        assertEquals(List.of("high"), captor.getValue().stream().map(RetrievedChunk::chunkId).toList());
    }

    /** topK 只截断结果，统一阈值只过滤低向量分，两者都不改变剩余候选顺序。 */
    @Test
    void topKAndThresholdDoNotChangeCandidateRanking() {
        var retriever = new KnowledgeRetriever(mock(VectorStore.class),
                new RetrievalProperties(3, 0), new LexicalRrfRanker());
        var candidates = List.of(document("a", "无关", 0.9),
                document("b", "线程耗尽", 0.8),
                document("c", "执行器线程耗尽", 0.7),
                document("d", "无关文字", 0.6));
        for (RetrievalMode mode : RetrievalMode.values()) {
            var full = retriever.rankCandidates("线程耗尽", candidates, mode, 20, 0);
            assertEquals(full.subList(0, 1), retriever.rankCandidates("线程耗尽", candidates, mode, 1, 0));
            assertEquals(full.subList(0, 3), retriever.rankCandidates("线程耗尽", candidates, mode, 3, 0));
            var expected = full.stream().filter(chunk -> chunk.score() >= 0.75).toList();
            assertEquals(expected, retriever.rankCandidates("线程耗尽", candidates, mode, 20, 0.75));
        }
    }

    /** 经 Spring Boot Binder 装配时，缺省模式为 HYBRID，显式 C 也能正确绑定。 */
    @Test
    void retrievalModeBindsThroughConfigurationPropertiesConstructor() {
        var defaults = new Binder(new MapConfigurationPropertySource(Map.of(
                "rag.retrieval.top-k", "3", "rag.retrieval.threshold", "0.47")))
                .bindOrCreate("rag.retrieval", RetrievalProperties.class);
        assertEquals(RetrievalMode.HYBRID, defaults.mode());
        var reranked = new Binder(new MapConfigurationPropertySource(Map.of(
                "rag.retrieval.top-k", "3", "rag.retrieval.threshold", "0.47",
                "rag.retrieval.mode", "HYBRID_RERANK")))
                .bindOrCreate("rag.retrieval", RetrievalProperties.class);
        assertEquals(RetrievalMode.HYBRID_RERANK, reranked.mode());
    }

    /** 测试候选均具备生产检索要求的有效来源元数据。 */
    private Document document(String id, String text, double score) {
        return Document.builder().id(id).text(text).score(score)
                .metadata(Map.of("source", "mysql-slow-query.md", "domain", "mysql",
                        "title", "测试", "section", "诊断")).build();
    }
}
