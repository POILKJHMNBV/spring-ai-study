package org.example.ai.eval;

import org.example.ai.SpringAiStudyApplication;
import org.example.ai.rag.KnowledgeRetriever;
import org.example.ai.rag.RetrievalMode;
import org.example.ai.rag.RetrievedChunk;
import org.example.ai.rag.MarkdownSectionSplitter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 显式运行：mvn test -Dtest=RetrievalEvaluationIT。需要 Day11 所用 VM PostgreSQL/pgvector
 * 和本地 Ollama BGE-M3；Spring 应用真实索引语料，测试不调用生成 LLM，也不替换内存向量库。
 */
class RetrievalEvaluationIT {
    private static final int CANDIDATE_K = 20;
    private static final int WARMUP = 1;
    private static final int SAMPLES = 3;
    private static final List<Double> THRESHOLDS = List.of(0.0, 0.47);
    private static final List<RetrievalMode> MODES = List.of(RetrievalMode.VECTOR,
            RetrievalMode.HYBRID, RetrievalMode.HYBRID_RERANK);

    /** 一种排序模式与一次向量阈值配置的组合键。 */
    private record RankKey(RetrievalMode mode, double threshold) {
    }

    /** 一次查询样本的共享候选耗时及各配置独立排序结果。 */
    private record Sample(long candidateNanos, Map<RankKey, Long> rankingNanos,
                          Map<RankKey, List<RetrievedChunk>> rankings) {
    }

    /** 一例一模式的指标和中位耗时，用于逐例与分组汇总。 */
    private record Row(RetrievalCases.Case testCase, RankKey key, RetrievalMetrics.Score score,
                       List<RetrievedChunk> hits, double candidateMs, double rankingMs) {
        /** 共享候选耗时加本配置的排序耗时。 */
        double totalMs() {
            return candidateMs + rankingMs;
        }
    }

    /** 在独立 PG schema 上索引真实语料，并以同批候选对 A/B/C 评估。 */
    @Test
    void evaluateSameRealCandidatesAcrossThreeModes() throws Exception {
        List<RetrievalCases.Case> cases = RetrievalCases.load();
        JdbcTemplate jdbc = jdbc();
        String schema = "day12_eval_" + UUID.randomUUID().toString().replace("-", "");
        Path manifest = Path.of("target", "day12", schema, "index-manifest.json");
        // schema 字符只由固定前缀与 UUID 十六进制组成，且仅清理本次成功创建的对象。
        boolean created = false;
        try {
            jdbc.execute("CREATE SCHEMA " + schema);
            created = true;
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SpringAiStudyApplication.class)
                    .web(WebApplicationType.NONE).run("--spring.profiles.active=local", "--app.ops.data-source=MOCK",
                            "--spring.ai.vectorstore.pgvector.schema-name=" + schema,
                            "--rag.index.manifest-path=" + manifest)) {
                assertInstanceOf(PgVectorStore.class, context.getBean(org.springframework.ai.vectorstore.VectorStore.class));
                assertEquals(23, jdbc.queryForObject("SELECT count(*) FROM " + schema + ".vector_store", Integer.class));
                KnowledgeRetriever retriever = context.getBean(KnowledgeRetriever.class);
                // 第一轮预热 JVM/JDBC/Ollama。计时轮每次每题只搜索一次，三种模式使用同一批 Document。
                for (int warmup = 0; warmup < WARMUP; warmup++) {
                    for (var testCase : cases) measure(retriever, testCase.query());
                }
                List<Row> rows = new ArrayList<>();
                for (var testCase : cases) {
                    List<Sample> samples = new ArrayList<>();
                    for (int sample = 0; sample < SAMPLES; sample++) {
                        samples.add(measure(retriever, testCase.query()));
                    }
                    // 排名和指标取第一个正式计时轮；耗时用三轮中位数，避免偶发抖动。
                    Sample observed = samples.get(0);
                    double candidateMs = median(samples.stream().mapToDouble(s -> ms(s.candidateNanos())).toArray());
                    for (double threshold : THRESHOLDS) {
                        for (RetrievalMode mode : MODES) {
                            RankKey key = new RankKey(mode, threshold);
                            List<RetrievedChunk> hits = observed.rankings().get(key);
                            RetrievalMetrics.Score score = RetrievalMetrics.scoreChunks(testCase.expectedSources(), hits);
                            double rankingMs = median(samples.stream()
                                    .mapToDouble(s -> ms(s.rankingNanos().get(key))).toArray());
                            rows.add(new Row(testCase, key, score, hits, candidateMs, rankingMs));
                        }
                    }
                }
                writeReport(rows, cases, manifest, schema, jdbc);
            }
        } finally {
            if (created) jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    /** 一次真实向量候选检索后依次排序；所有模式必须只排序传入候选。 */
    private Sample measure(KnowledgeRetriever retriever, String query) {
        long started = System.nanoTime();
        List<Document> candidates = retriever.searchCandidates(query);
        long candidateNanos = System.nanoTime() - started;
        assertFalse(candidates.isEmpty(), "索引为空时评估无效");
        assertEquals(CANDIDATE_K, candidates.size(), "当前 23 块语料每题应取满固定 20 候选");
        Set<String> candidateIds = new HashSet<>();
        for (Document candidate : candidates) {
            assertTrue(candidateIds.add(candidate.getId()), "向量候选 chunkId 重复");
            assertTrue(RetrievalCases.CORPUS.contains(candidate.getMetadata().get("source")), "未知知识来源");
        }
        Map<RankKey, Long> rankingNanos = new HashMap<>();
        Map<RankKey, List<RetrievedChunk>> rankings = new HashMap<>();
        for (double threshold : THRESHOLDS) {
            for (RetrievalMode mode : MODES) {
                RankKey key = new RankKey(mode, threshold);
                long rankStarted = System.nanoTime();
                List<RetrievedChunk> ranked = retriever.rankCandidates(query, candidates, mode, CANDIDATE_K, threshold);
                rankingNanos.put(key, System.nanoTime() - rankStarted);
                if (threshold == 0) {
                    assertEquals(candidates.size(), ranked.size(), "threshold=0、topK=20 应完整排名同一候选");
                }
                Set<String> rankedIds = new HashSet<>();
                for (RetrievedChunk hit : ranked) {
                    assertTrue(candidateIds.contains(hit.chunkId()), "排序阶段不得增加新候选");
                    assertTrue(rankedIds.add(hit.chunkId()), "同一候选不得出现两次");
                    assertTrue(RetrievalCases.CORPUS.contains(hit.source()), "排名来源必须来自知识语料");
                }
                rankings.put(key, List.copyOf(ranked));
            }
        }
        return new Sample(candidateNanos, rankingNanos, rankings);
    }

    /** TSV 保留逐例的完整前 20 source/chunk 顺序；汇总按 dev/validation 独立聚合。 */
    private void writeReport(List<Row> rows, List<RetrievalCases.Case> cases, Path manifest, String schema,
                             JdbcTemplate jdbc)
            throws Exception {
        Path directory = Path.of("target", "day12");
        Files.createDirectories(directory);
        StringBuilder detail = new StringBuilder("id\tsplit\tkind\tmode\tthreshold\tquery\texpectedSources\t"
                + "candidateMsMedian\trankingMsMedian\ttotalMsSumOfMedians\trecallAt1\trecallAt3\t"
                + "mrrAt20\tfirstRelevantChunkRank\trankedSources\trankedChunkIds\n");
        for (Row row : rows) {
            detail.append(row.testCase().id()).append('\t').append(row.testCase().split()).append('\t')
                    .append(row.testCase().kind()).append('\t').append(row.key().mode()).append('\t')
                    .append(row.key().threshold()).append('\t')
                    .append(row.testCase().query()).append('\t')
                    .append(String.join(",", row.testCase().expectedSources().stream().sorted().toList())).append('\t')
                    .append(format(row.candidateMs())).append('\t').append(format(row.rankingMs())).append('\t')
                    .append(format(row.totalMs())).append('\t').append(format(row.score().recallAt1())).append('\t')
                    .append(format(row.score().recallAt3())).append('\t').append(format(row.score().mrrAt20()))
                    .append('\t').append(row.score().firstRelevantRank()).append('\t')
                    .append(String.join(",", row.hits().stream().map(RetrievedChunk::source).toList())).append('\t')
                    .append(String.join(",", row.hits().stream().map(RetrievedChunk::chunkId).toList())).append('\n');
        }
        Files.writeString(directory.resolve("per-case.tsv"), detail, StandardCharsets.UTF_8);

        String datasetHash = MarkdownSectionSplitter.sha256(
                Files.readString(Path.of("src", "test", "resources", RetrievalCases.RESOURCE), StandardCharsets.UTF_8));
        var indexManifest = JsonMapper.builder().build().readTree(Files.readString(manifest, StandardCharsets.UTF_8));
        String pgVersion = jdbc.queryForObject("SHOW server_version", String.class);
        String vectorVersion = jdbc.queryForObject(
                "SELECT extversion FROM pg_extension WHERE extname='vector'", String.class);
        StringBuilder summary = new StringBuilder("# Day12 Retrieval Evaluation\n\n")
                .append("Generated: ").append(Instant.now()).append("\n\n")
                .append("- Dataset: `src/test/resources/").append(RetrievalCases.RESOURCE)
                .append("` (SHA-256 `").append(datasetHash).append("`; ").append(cases.size()).append(" cases)\n")
                .append("- Validation includes the four known Day12 plan queries; no parameters were tuned on validation.\n")
                .append("- Index manifest: `").append(manifest.toString().replace('\\', '/'))
                .append("` (isolated schema `").append(schema).append("`, removed after test)\n")
                .append("- Corpus SHA-256: `").append(indexManifest.path("corpusHash").asString()).append("`; ")
                .append("chunks: ").append(indexManifest.path("chunks").asInt()).append("; splitter: `")
                .append(indexManifest.path("splitterVersion").asString()).append("`.\n")
                .append("- Backend: PostgreSQL ").append(pgVersion).append(", pgvector ").append(vectorVersion)
                .append("; embedding model `").append(indexManifest.path("model").asString())
                .append("` digest `").append(indexManifest.path("digest").asString()).append("`, dimensions ")
                .append(indexManifest.path("dimensions").asInt()).append(".\n")
                .append("- Settings: top 20 shared vector candidates, thresholds 0 (full-rank diagnostic) ")
                .append("and 0.47 (current production setting), one warmup and three measured samples per query.\n")
                .append("- A=VECTOR; B=HYBRID (vector + lexical RRF); C=HYBRID_RERANK. No generation LLM calls.\n")
                .append("- Recall@1/@3: first 1/3 **chunks**, then distinct matching sources / gold source count. ")
                .append("MRR@20: reciprocal position of first relevant source in the filtered chunk ranking, zero if absent.\n")
                .append("- Latency: median shared candidate retrieval + median mode-specific ranking per query; ")
                .append("the same candidate latency appears in all modes and thresholds. These are sequential local measurements, ")
                .append("not an isolated production benchmark.\n\n")
                .append("| Split | Threshold | Mode | Cases | Recall@1 | Recall@3 | MRR@20 | Candidate ms | Ranking ms | Total ms |\n")
                .append("|---|---:|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (String split : List.of("dev", "validation")) {
            for (double threshold : THRESHOLDS) {
                for (RetrievalMode mode : MODES) {
                    List<Row> group = rows.stream().filter(r -> r.testCase().split().equals(split)
                            && r.key().mode() == mode && r.key().threshold() == threshold).toList();
                    summary.append("| ").append(split).append(" | ").append(threshold).append(" | ")
                            .append(mode).append(" | ").append(group.size())
                        .append(" | ").append(format(group.stream().mapToDouble(r -> r.score().recallAt1()).average().orElse(0)))
                        .append(" | ").append(format(group.stream().mapToDouble(r -> r.score().recallAt3()).average().orElse(0)))
                        .append(" | ").append(format(group.stream().mapToDouble(r -> r.score().mrrAt20()).average().orElse(0)))
                        .append(" | ").append(format(group.stream().mapToDouble(Row::candidateMs).average().orElse(0)))
                        .append(" | ").append(format(group.stream().mapToDouble(Row::rankingMs).average().orElse(0)))
                        .append(" | ").append(format(group.stream().mapToDouble(Row::totalMs).average().orElse(0)))
                            .append(" |\n");
                }
            }
        }
        summary.append("\nMetrics are observations; the test does not require C to outperform A or B. ")
                .append("Reproduce with `mvn test -Dtest=RetrievalEvaluationIT` while the Day11 VM DB and BGE-M3 are available.\n");
        Files.writeString(directory.resolve("summary.md"), summary, StandardCharsets.UTF_8);
    }

    /** 奇数次独立采样的中位数，降低一次网络/GC 抖动的影响。 */
    private double median(double[] values) {
        Arrays.sort(values);
        return values[values.length / 2];
    }

    /** 单调时钟纳秒转毫秒，避免墙钟跳变。 */
    private double ms(long nanos) {
        return nanos / 1_000_000.0;
    }

    /** 固定小数格式，避免不同机器 Locale 生成不同分隔符。 */
    private String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    /** 使用与 Day11 IT 相同的配置读取方式连接现有 VM，不修改公共 schema。 */
    private JdbcTemplate jdbc() throws Exception {
        var environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))
                .forEach(environment.getPropertySources()::addLast);
        var datasource = new DriverManagerDataSource();
        datasource.setUrl(environment.getRequiredProperty("spring.datasource.url"));
        datasource.setUsername(environment.getRequiredProperty("spring.datasource.username"));
        datasource.setPassword(environment.getRequiredProperty("spring.datasource.password"));
        return new JdbcTemplate(datasource);
    }
}
