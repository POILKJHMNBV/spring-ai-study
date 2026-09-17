package org.example.ai.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Component
public class KnowledgeRetriever {
    private final VectorStore vectorStore;
    private final RetrievalProperties properties;
    private final LexicalRrfRanker ranker;

    public KnowledgeRetriever(VectorStore vectorStore, RetrievalProperties properties, LexicalRrfRanker ranker) {
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.ranker = ranker;
    }

    public List<RetrievedChunk> retrieve(String query) {
        return retrieve(query, properties.topK(), properties.threshold());
    }

    public List<RetrievedChunk> retrieve(String query, Integer topK, Double threshold) {
        int limit = topK == null ? properties.topK() : topK;
        double cutoff = threshold == null ? properties.threshold() : threshold;
        RetrievalProperties.validate(query, limit, cutoff);

        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // 不根据问题硬编码 domain 过滤：跨组件因果问题也需要召回关联知识。
        // 固定候选窗口，避免 topK 或阈值改变 BM25 文档频率，导致相同问题排序漂移。
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query.strip())
                .topK(20)
                .similarityThreshold(0)
                .build();
        List<Document> candidates = vectorStore.similaritySearch(searchRequest);
        candidates.forEach(this::validateSource);
        List<RetrievedChunk> chunks = ranker.rank(query.strip(), candidates).stream()
                .filter(document -> Optional.ofNullable(document.getScore()).orElse(0.0) >= cutoff)
                .limit(limit).map(this::toChunk).toList();

        stopWatch.stop();
        log.info("RAG query={} topK={} threshold={} hits={} elapsedMs={}",
                query.replaceAll("[\\r\\n\\t]", " "), limit, cutoff, chunks.size(),
                stopWatch.getTotalTimeMillis());

        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk hit = chunks.get(i);
            // score 是向量相似度，不是诊断结论的置信概率。
            log.info("RAG rank={} source={} section={} domain={} chunkId={} score={}",
                    i + 1, hit.source(), hit.section(), hit.domain(), hit.chunkId(), hit.score());
        }
        return chunks;
    }

    private void validateSource(Document document) {
        // 词法匹配不能让无来源或来源归属错误的内容进入回答上下文。
        String source = String.valueOf(document.getMetadata().get("source"));
        String expectedDomain;
        try {
            expectedDomain = MarkdownSectionSplitter.domain(source);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("索引候选来源未登记: " + source, exception);
        }
        if (!expectedDomain.equals(document.getMetadata().get("domain"))
                || !(document.getMetadata().get("section") instanceof String section) || section.isBlank()) {
            throw new IllegalStateException("索引候选元数据不完整或归属错误: " + source);
        }
    }

    private RetrievedChunk toChunk(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        Object index = metadata.getOrDefault("chunkIndex", -1);
        return new RetrievedChunk(
                String.valueOf(metadata.getOrDefault("source", "unknown")),
                String.valueOf(metadata.getOrDefault("title", "unknown")),
                String.valueOf(metadata.getOrDefault("domain", "unknown")),
                String.valueOf(metadata.getOrDefault("section", "unknown")),
                document.getId(), index instanceof Number number ? number.intValue() : -1,
                document.getScore(), Objects.requireNonNullElse(document.getText(), ""));
    }
}
