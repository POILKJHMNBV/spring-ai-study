package org.example.ai.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.mockito.ArgumentCaptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 无模型依赖的回归测试：验证切分内容完整性、稳定标识及检索边界。 */
class RagRegressionTest {
    private final MarkdownSectionSplitter splitter = new MarkdownSectionSplitter();

    @Test
    void longSectionsKeepEveryCodePointAndHeadingWithinBudget() {
        String body = "数据库等待锁，检查执行计划🙂。".repeat(100);
        String prefix = "# 数据库指南\n\n## 1. 诊断\n\n";
        var chunks = splitter.split(prefix + body, "mysql-slow-query.md");
        assertTrue(chunks.size() > 2);
        var rebuilt = new StringBuilder();
        var estimator = new JTokkitTokenCountEstimator();
        for (Document chunk : chunks) {
            assertTrue(chunk.getText().startsWith(prefix));
            assertTrue(estimator.estimate(chunk.getText()) <= MarkdownSectionSplitter.TOKEN_BUDGET);
            assertEquals("mysql", chunk.getMetadata().get("domain"));
            rebuilt.append(chunk.getText().substring(prefix.length()));
        }
        assertEquals(body, rebuilt.toString(), "二次切分不得漏字或破坏代理对");
    }

    @Test
    void idsStayStableWhenEarlierSectionIsInserted() {
        String text = "# 指南\n\n## 原小节\n\n原有内容。";
        var before = splitter.split(text, "mysql-slow-query.md");
        var after = splitter.split(text.replace("## 原小节", "## 新小节\n新增内容。\n\n## 原小节"), "mysql-slow-query.md");
        assertEquals(before.get(0).getId(), after.get(1).getId());
        assertNotEquals(before.get(0).getMetadata().get("chunkIndex"), after.get(1).getMetadata().get("chunkIndex"));
    }

    @Test
    void unknownSourcesAndOversizedHeadingsFailExplicitly() {
        assertThrows(IllegalArgumentException.class, () -> splitter.split("正文", "unknown.md"));
        assertThrows(IllegalArgumentException.class, () -> splitter.split("# 标题".repeat(500) + "\n正文", "mysql-slow-query.md"));
    }

    @Test
    void fencedHeadingsRemainInTheBody() {
        var chunks = splitter.split("# 指南\n## 正文\n```text\n## 示例不是小节\n```\n继续正文", "mysql-slow-query.md");
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).getText().contains("## 示例不是小节"));
        assertEquals("正文", chunks.get(0).getMetadata().get("section"));
    }

    @Test
    void originalCorpusKeepsTwentyOneChunksAndIdenticalText() throws Exception {
        // 小节未超预算时，新切分器不能改变 P0 对照语料。
        int count = 0;
        try (var paths = Files.list(Path.of("src/test/resources/rag/baseline"))) {
            for (Path path : paths.toList()) {
                String text = Files.readString(path);
                var current = splitter.split(text, path.getFileName().toString());
                var baseline = BaselineSectionSplitter.split(text, path.getFileName().toString());
                assertEquals(baseline.stream().map(Document::getText).toList(), current.stream().map(Document::getText).toList());
                count += current.size();
            }
        }
        assertEquals(21, count);
    }

    @Test
    void invalidInputsNeverCallEmbeddingStore() {
        var store = mock(VectorStore.class);
        var retriever = new KnowledgeRetriever(store, new RetrievalProperties(3, 0.47), new LexicalRrfRanker());
        for (String query : new String[]{null, "", " \n ", "x".repeat(2001)}) {
            assertThrows(IllegalArgumentException.class, () -> retriever.retrieve(query));
        }
        for (double threshold : new double[]{-1, 1.1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> retriever.retrieve("查询", 3, threshold));
        }
        assertThrows(IllegalArgumentException.class, () -> retriever.retrieve("查询", 0, null));
        assertThrows(IllegalArgumentException.class, () -> retriever.retrieve("查询", 21, null));
        verifyNoInteractions(store);
    }

    @Test
    void lexicalEvidenceCannotBypassThresholdAndSourceMetadataSurvives() {
        var store = mock(VectorStore.class);
        var relevant = Document.builder().id("valid").text("数据库索引执行计划").score(0.8)
                .metadata(Map.of("source", "mysql-slow-query.md", "domain", "mysql", "section", "3. 原因", "chunkIndex", 2)).build();
        var lowScore = Document.builder().id("low").text("RejectedExecutionException").score(0.3)
                .metadata(Map.of("source", "thread-pool-exhaustion.md", "domain", "thread-pool", "section", "2. 异常")).build();
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(relevant, lowScore));
        var retriever = new KnowledgeRetriever(store, new RetrievalProperties(3, 0.47), new LexicalRrfRanker());
        var result = retriever.retrieve("RejectedExecutionException");
        assertEquals(1, result.size());
        assertEquals("mysql-slow-query.md", result.get(0).source());
        assertEquals("mysql", result.get(0).domain());
        assertEquals("3. 原因", result.get(0).section());
        assertEquals(0.8, result.get(0).score());
        var request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(store).similaritySearch(request.capture());
        assertEquals(20, request.getValue().getTopK());
        assertNull(request.getValue().getFilterExpression(), "领域元数据不得用于问题路由");
        assertEquals(2, retriever.retrieve("RejectedExecutionException", 3, 0.0).size());
    }

    @Test
    void keywordMatchCannotBypassSourceValidation() {
        var store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder().text("RejectedExecutionException").score(0.99).build()));
        var retriever = new KnowledgeRetriever(store, new RetrievalProperties(3, 0.47), new LexicalRrfRanker());
        assertThrows(IllegalStateException.class, () -> retriever.retrieve("RejectedExecutionException"));
    }
}
