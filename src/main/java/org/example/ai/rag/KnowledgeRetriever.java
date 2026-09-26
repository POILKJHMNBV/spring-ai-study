package org.example.ai.rag;

import lombok.extern.slf4j.Slf4j;
import org.example.ai.observability.AgentTelemetry;
import org.example.ai.rag.rerank.DocumentReranker;
import org.example.ai.rag.rerank.LexicalRrfRanker;
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
    private final AgentTelemetry telemetry;

    /** 兼容已有手工构建；正式 Spring Bean 采用带重排器的构造器。 */
    public KnowledgeRetriever(VectorStore vectorStore, RetrievalProperties properties, LexicalRrfRanker ranker) {
        this(vectorStore, properties, ranker, new TokenCoverageReranker(), null);
    }

    /** 注入同一个无外部服务的重排器，三个检索模式共用候选检索逻辑。 */
    public KnowledgeRetriever(VectorStore vectorStore, RetrievalProperties properties,
                              LexicalRrfRanker ranker, DocumentReranker reranker) {
        this(vectorStore, properties, ranker, reranker, null);
    }

    /** Spring 注入统一观测组件，以真实候选数与最终命中数记录检索效果。 */
    @Autowired
    public KnowledgeRetriever(VectorStore vectorStore, RetrievalProperties properties,
                              LexicalRrfRanker ranker, DocumentReranker reranker, AgentTelemetry telemetry) {
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.ranker = ranker;
        this.reranker = reranker;
        this.telemetry = telemetry;
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

    /**
     * 显式模式、输出条数与统一向量阈值的完整检索入口。
     *
     * <p>流程：参数归一化 → 输入校验 → 向量搜索候选 → 排序/重排 → 阈值过滤 → 截取 topK → 日志审计。
     * 所有重载的 {@code retrieve} 最终都汇聚到此方法，保证检索路径唯一且可观测。</p>
     *
     * @param query     用户问题，不能为空或空白
     * @param mode      检索模式（VECTOR / HYBRID_RRF / HYBRID_RERANK），不能为 null
     * @param topK      期望返回条数；为 null 时回退到配置默认值
     * @param threshold 向量相似度阈值；为 null 时回退到配置默认值
     * @return 按排序后的检索结果，不超过 topK 条
     */
    public List<RetrievedChunk> retrieve(String query, RetrievalMode mode, Integer topK, Double threshold) {
        // 参数归一化：null 回退到配置默认值，避免每个调用方重复判断。
        int limit = topK == null ? properties.topK() : topK;
        double cutoff = threshold == null ? properties.threshold() : threshold;
        // 统一校验：query 非空、topK > 0、threshold 在合法范围内。
        RetrievalProperties.validate(query, limit, cutoff);
        if (mode == null) throw new IllegalArgumentException("mode 不能为空");

        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        // 先搜索 20 个候选（固定窗口），再按模式排序、过滤、截取。
        List<Document> candidates;
        List<RetrievedChunk> chunks;
        try {
            candidates = searchCandidates(query);
            chunks = rankCandidates(query, candidates, mode, limit, cutoff);
        } catch (RuntimeException error) {
            stopWatch.stop();
            if (telemetry != null) {
                telemetry.retrievalFailed(stopWatch.getTotalTimeNanos());
            }
            throw error;
        }
        stopWatch.stop();
        if (telemetry != null) {
            telemetry.retrievalFinished(stopWatch.getTotalTimeNanos(), candidates.size(), chunks.size());
        }
        // 汇总日志只输出配置和数量，不记录可能带凭据的查询正文。
        log.info("RAG mode={} topK={} threshold={} hits={} elapsedMs={}",
                mode, limit, cutoff, chunks.size(),
                stopWatch.getTotalTimeMillis());
        // 逐条日志：记录每个结果的排名和元数据，便于排查检索质量。
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
     *
     * <p>设计要点：
     * <ul>
     *   <li>固定 20 个候选：为不同排序模式提供稳定的候选池，保证评测结果可比</li>
     *   <li>阈值设为 0：不过滤任何候选，让后续排序模式自行决定业务阈值</li>
     *   <li>不做 domain 过滤：跨组件因果问题（如“线程池满导致 Kafka 积压”）需要召回多个领域的知识</li>
     * </ul>
     * </p>
     *
     * @param query 用户问题，不能为空或空白
     * @return 不可变的候选列表，已按向量相似度降序排列
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
        // 逐个校验来源：拒绝无来源、未登记或元数据不完整的候选，防止污染检索结果。
        candidates.forEach(this::validateSource);
        return List.copyOf(candidates);
    }

    /**
     * 纯排序入口：不会调用向量库或修改传入候选。
     *
     * <p>流程：
     * <ol>
     *   <li>在完整候选集上计算排序（VECTOR 模式保持原序，HYBRID_RRF 计算 RRF 顺序）</li>
     *   <li>按原始向量分过滤低于阈值的候选——先排序后过滤，避免阈值影响 BM25 统计</li>
     *   <li>HYBRID_RERANK 模式调用重排器，其他模式直接截取 topK</li>
     * </ol>
     * </p>
     *
     * @param query      用户问题，用于词法排序和重排
     * @param candidates 向量搜索返回的候选列表，不会被修改
     * @param mode       检索模式（VECTOR / HYBRID_RRF / HYBRID_RERANK）
     * @param topK       期望返回条数
     * @param threshold  向量相似度阈值，低于此值的候选会被过滤
     * @return 排序后的检索结果，不超过 topK 条
     */
    public List<RetrievedChunk> rankCandidates(String query, List<Document> candidates,
                                               RetrievalMode mode, int topK, double threshold) {
        RetrievalProperties.validate(query, topK, threshold);
        if (mode == null || candidates == null) throw new IllegalArgumentException("mode 和 candidates 不能为空");
        // 不可变副本：防止后续操作修改原始候选列表，保证调用方数据安全。
        List<Document> snapshot = List.copyOf(candidates);
        snapshot.forEach(this::validateSource);
        // VECTOR 模式保持原始向量相似度顺序；其他模式先计算 RRF 顺序，供后续过滤和重排使用。
        List<Document> ordered = mode == RetrievalMode.VECTOR ? snapshot : ranker.rank(query.strip(), snapshot);
        // 按原始向量分过滤：阈值过滤在排序之后，确保 BM25 统计不受阈值影响。
        List<RetrievedChunk> eligible = ordered.stream()
                .filter(document -> Optional.ofNullable(document.getScore()).orElse(0.0) >= threshold)
                .map(this::toChunk).toList();
        // HYBRID_RERANK 模式调用重排器（如 TokenCoverageReranker），其他模式直接截取 topK。
        if (mode == RetrievalMode.HYBRID_RERANK) {
            return reranker.rerank(query.strip(), eligible, topK);
        }
        return eligible.stream().limit(topK).toList();
    }

    /**
     * 拒绝无来源、未知来源或领域归属不符的索引候选。
     *
     * <p>校验内容：
     * <ul>
     *   <li>来源文件名必须已登记在 {@link MarkdownSectionSplitter#domain} 中，否则直接失败</li>
     *   <li>文档的 {@code domain} 元数据必须与文件名对应的领域一致，防止归属错误</li>
     *   <li>文档必须有非空的 {@code section} 元数据，确保检索结果能定位到具体小节</li>
     * </ul>
     * </p>
     *
     * @param document 向量库返回的候选文档
     * @throws IllegalStateException 来源未登记、归属错误或元数据不完整
     */
    private void validateSource(Document document) {
        // 词法匹配不能让无来源或来源归属错误的内容进入回答上下文。
        String source = String.valueOf(document.getMetadata().get("source"));
        // 第一步：检查来源是否已登记，未登记的文件名会直接抛出异常。
        String expectedDomain;
        try {
            expectedDomain = MarkdownSectionSplitter.domain(source);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("索引候选来源未登记: " + source, exception);
        }
        // 第二步：检查 domain 元数据是否与文件名对应的领域一致，防止数据污染。
        // 第三步：检查 section 元数据是否存在且非空，确保检索结果可定位到具体小节。
        if (!expectedDomain.equals(document.getMetadata().get("domain"))
                || !(document.getMetadata().get("section") instanceof String section) || section.isBlank()) {
            throw new IllegalStateException("索引候选元数据不完整或归属错误: " + source);
        }
    }

    /**
     * 将官方 {@link Document} 转为上下文 DTO {@link RetrievedChunk}，原样保留向量分。
     *
     * <p>转换策略：
     * <ul>
     *   <li>元数据字段使用 {@code getOrDefault} 提供安全默认值，避免缺失字段导致 NPE</li>
     *   <li>{@code chunkIndex} 可能是非 Number 类型（如 JSON 反序列化后的字符串），需额外校验</li>
     *   <li>{@code score} 是向量相似度，直接透传，不转换为置信概率</li>
     * </ul>
     * </p>
     *
     * @param document 向量库返回的候选文档
     * @return 包含来源、标题、领域、小节、块索引、向量分和正文的 DTO
     */
    private RetrievedChunk toChunk(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        // chunkIndex 可能是非 Number 类型，安全回退到 -1 表示未知。
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
