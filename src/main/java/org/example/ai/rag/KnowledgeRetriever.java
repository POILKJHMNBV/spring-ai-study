package org.example.ai.rag;

import lombok.extern.slf4j.Slf4j;
import org.example.ai.rag.rerank.DocumentReranker;
import org.example.ai.rag.rerank.TokenCoverageReranker;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 固定候选搜索与 A/B/C 排序入口；各模式共用来源校验和向量阈值。 */
@Slf4j
@Component
public class KnowledgeRetriever {
    private final VectorStore vectorStore;
    private final RetrievalProperties properties;
    private final LexicalRrfRanker ranker;
    private final DocumentReranker reranker;

    /** 兼容已有手工构建；正式 Spring Bean 采用带重排器的构造器。 */
    public KnowledgeRetriever(VectorStore vectorStore, RetrievalProperties properties, LexicalRrfRanker ranker) {
        this(vectorStore, properties, ranker, new TokenCoverageReranker());
    }

    /** 注入同一个无外部服务的重排器，三个检索模式共用候选检索逻辑。 */
    @Autowired
    public KnowledgeRetriever(VectorStore vectorStore, RetrievalProperties properties,
                              LexicalRrfRanker ranker, DocumentReranker reranker) {
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.ranker = ranker;
        this.reranker = reranker;
    }

    /** 按配置模式、topK 和阈值执行生产检索。 */
    public List<RetrievedChunk> retrieve(String query) {
        return retrieve(query, properties.mode(), properties.topK(), properties.threshold());
    }

    /** 保持调试接口原有参数，并沿用配置中的排序模式。 */
    public List<RetrievedChunk> retrieve(String query, Integer topK, Double threshold) {
        return retrieve(query, properties.mode(), topK, threshold);
    }

    /** 显式选择 A/B/C 模式；适合运行时比较，候选窗口仍固定为 20。 */
    public List<RetrievedChunk> retrieve(String query, RetrievalMode mode) {
        return retrieve(query, mode, properties.topK(), properties.threshold());
    }

    /** 显式模式、输出条数与统一向量阈值的完整检索入口。 */
    public List<RetrievedChunk> retrieve(String query, RetrievalMode mode, Integer topK, Double threshold) {
        int limit = topK == null ? properties.topK() : topK;
        double cutoff = threshold == null ? properties.threshold() : threshold;
        RetrievalProperties.validate(query, limit, cutoff);
        if (mode == null) throw new IllegalArgumentException("mode 不能为空");

        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        List<RetrievedChunk> chunks = rankCandidates(query, searchCandidates(query), mode, limit, cutoff);
        stopWatch.stop();
        log.info("RAG mode={} query={} topK={} threshold={} hits={} elapsedMs={}",
                mode, query.replaceAll("[\\r\\n\\t]", " "), limit, cutoff, chunks.size(),
                stopWatch.getTotalTimeMillis());
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk hit = chunks.get(i);
            // score 是向量相似度，不是诊断结论的置信概率。
            log.info("RAG rank={} source={} section={} domain={} chunkId={} score={}",
                    i + 1, hit.source(), hit.section(), hit.domain(), hit.chunkId(), hit.score());
        }
        return chunks;
    }

    /**
     * 只执行一次向量搜索，供评测对同一批候选分别运行 A/B/C 排序。
     * 固定 20 个候选及搜索阈值 0，避免 topK/业务阈值改变候选分布。
     */
    public List<Document> searchCandidates(String query) {
        RetrievalProperties.validate(query, 20, 0);
        // 不根据问题硬编码 domain 过滤：跨组件因果问题也需要召回关联知识。
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query.strip())
                .topK(20)
                .similarityThreshold(0)
                .build();
        List<Document> candidates = vectorStore.similaritySearch(searchRequest);
        candidates.forEach(this::validateSource);
        return List.copyOf(candidates);
    }

    /**
     * 纯排序入口：不会调用向量库或修改传入候选。先在完整候选集上计算
     * 原始向量顺序或现有 RRF 顺序，避免阈值影响 BM25 统计；随后统一按
     * 原始向量分过滤，必要时重排，最后截取 topK。
     */
    public List<RetrievedChunk> rankCandidates(String query, List<Document> candidates,
                                               RetrievalMode mode, int topK, double threshold) {
        RetrievalProperties.validate(query, topK, threshold);
        if (mode == null || candidates == null) throw new IllegalArgumentException("mode 和 candidates 不能为空");
        List<Document> snapshot = List.copyOf(candidates);
        snapshot.forEach(this::validateSource);
        List<Document> ordered = mode == RetrievalMode.VECTOR ? snapshot : ranker.rank(query.strip(), snapshot);
        List<RetrievedChunk> eligible = ordered.stream()
                .filter(document -> Optional.ofNullable(document.getScore()).orElse(0.0) >= threshold)
                .map(this::toChunk).toList();
        if (mode == RetrievalMode.HYBRID_RERANK) {
            return reranker.rerank(query.strip(), eligible, topK);
        }
        return eligible.stream().limit(topK).toList();
    }

    /** 拒绝无来源、未知来源或领域归属不符的索引候选。 */
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

    /** 将官方 Document 转为上下文 DTO，原样保留向量分。 */
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
