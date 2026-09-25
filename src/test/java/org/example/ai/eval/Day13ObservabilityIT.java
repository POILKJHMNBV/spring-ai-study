package org.example.ai.eval;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.ObservationView;
import org.example.ai.SpringAiStudyApplication;
import org.example.ai.harness.AgentRunResult;
import org.example.ai.harness.AgentRunner;
import org.example.ai.harness.TraceRecorder;
import org.example.ai.tool.mock.MockScenario;
import org.example.ai.tool.mock.OpsMockDataProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Day13 固定真实环境评测：每次创建独立 PGVector schema，并实际调用 BGE-M3 与 Ollama。
 *
 * <p>案例固定，工具数据使用既有 MockScenario，以免 HTTP 服务波动掩盖模型及检索耗时。
 * 此测试通过 {@code -Dtest=Day13ObservabilityIT} 显式运行；报告不包含原始问题、回答或凭据。
 * 报告中的 Trace 是业务事件时间线，跨组件父子关系另由 Observation 单测验证。</p>
 */
class Day13ObservabilityIT {
    private static final Path REPORT = Path.of("target", "eval-results", "day13-observability.json");
    private static final List<FixedCase> CASES = List.of(
            new FixedCase("D13-RAG", MockScenario.E01_THREAD_POOL_SATURATED,
                    "线程池满时，应该先检查哪些证据？"),
            new FixedCase("D13-TOOL", MockScenario.E01_THREAD_POOL_SATURATED,
                    "payment-service 的 order-topic 消费变慢，请检查服务和 Kafka 指标。"),
            new FixedCase("D13-DB", MockScenario.E03_DATABASE_SLOW,
                    "payment-service 的消费变慢，请重点核对数据库依赖的证据。")
    );
    private final JsonMapper mapper = JsonMapper.builder().build();

    /**
     * 对同一份真实知识索引依次执行固定案例，输出可复核的延迟分位数和组件计时。
     * 测试只要求至少一次成功及一次实际检索，模型判断质量由专门的 Agent Eval 负责。
     */
    @Test
    void fixedRealPgvectorAndOllamaEvaluation() throws Exception {
        String schema = "day13_it_" + UUID.randomUUID().toString().replace("-", "");
        Path manifest = Path.of("target", "day13", schema + ".json");
        JdbcTemplate jdbc = jdbc();
        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SpringAiStudyApplication.class)
                    .web(WebApplicationType.NONE)
                    .run("--spring.profiles.active=local", "--app.ops.data-source=MOCK",
                            "--spring.ai.ollama.chat.temperature=0.0", "--spring.ai.ollama.chat.seed=42",
                            "--spring.ai.vectorstore.pgvector.schema-name=" + schema,
                            "--rag.index.manifest-path=" + manifest)) {
                AgentRunner runner = context.getBean(AgentRunner.class);
                OpsMockDataProvider mockData = context.getBean(OpsMockDataProvider.class);
                MeterRegistry meters = context.getBean(MeterRegistry.class);
                List<ObservationEdge> observationTree = new CopyOnWriteArrayList<>();
                List<String> vectorHighCardinality = new CopyOnWriteArrayList<>();
                context.getBean(ObservationRegistry.class).observationConfig()
                        .observationHandler(new ObservationHandler<Observation.Context>() {
                            /** 接收实际执行中的所有标准 Observation。 */
                            @Override public boolean supportsContext(Observation.Context observation) {
                                return true;
                            }
                            /** 只保留阶段名和父阶段名，不将业务正文放进报告。 */
                            @Override public void onStop(Observation.Context observation) {
                                ObservationView parent = observation.getParentObservation();
                                observationTree.add(new ObservationEdge(observation.getName(),
                                        parent == null ? null : parent.getContextView().getName()));
                                if (observation.getName().contains("vector")) {
                                    vectorHighCardinality.add(observation.getHighCardinalityKeyValues().toString());
                                }
                            }
                        });
                List<CaseReport> cases = new ArrayList<>();
                try {
                    for (FixedCase fixed : CASES) {
                        String conversation = "day13-" + fixed.id().toLowerCase();
                        runner.clearMemory(conversation);
                        mockData.useScenario(fixed.scenario());
                        long begin = System.nanoTime();
                        AgentRunResult result = runner.run(fixed.prompt(), conversation, false);
                        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
                        cases.add(toReport(fixed.id(), result, elapsedMs));
                        runner.clearMemory(conversation);
                    }
                } finally {
                    mockData.reset();
                }
                writeReport(cases, meters, observationTree, resolveChatModel(context),
                        context.getEnvironment().getRequiredProperty("spring.ai.ollama.embedding.model"));
                assertTrue(cases.stream().anyMatch(c -> "COMPLETED".equals(c.status())),
                        "固定真实模型案例至少应有一次成功，详见 " + REPORT);
                assertTrue(cases.stream().anyMatch(c -> c.trace().stream()
                        .anyMatch(e -> "RAG".equals(e.type()))),
                        "真实 PGVector 检索必须进入 Agent Trace，详见 " + REPORT);
                assertTrue(cases.stream().mapToLong(CaseReport::toolCalls).sum() > 0,
                        "真实模型应至少触发一次 Tool 调用，详见 " + REPORT);
                assertEquals(CASES.size(), count(meters, "agent.requests"), 0.0001,
                        "每个固定案例都必须计入 Agent 请求数");
                assertEquals(CASES.size(), meters.find("agent.latency").timers().stream()
                        .mapToLong(Timer::count).sum(), "每个固定案例都必须被 Agent Timer 计时");
                assertEquals(cases.stream().mapToLong(CaseReport::promptTokens).sum(),
                        tokenTotal(meters, "llm.prompt.tokens"), 0.0001,
                        "已知 Prompt token 与业务 Trace、Micrometer Summary 应一致");
                assertEquals(cases.stream().mapToLong(CaseReport::completionTokens).sum(),
                        tokenTotal(meters, "llm.completion.tokens"), 0.0001,
                        "已知 Completion token 与业务 Trace、Micrometer Summary 应一致");
                assertTrue(observationTree.stream().anyMatch(e -> e.name().contains("agent")
                        && e.parent() == null), "缺少 Agent 请求根 Observation");
                assertTrue(observationTree.stream().anyMatch(e -> e.name().contains("llm")
                        && e.parent() != null), "缺少属于 Agent 请求的模型子 Observation");
                assertTrue(observationTree.stream().anyMatch(e -> e.name().contains("rag")
                        && e.parent() != null), "缺少属于 Agent 请求的检索子 Observation");
                assertTrue(!vectorHighCardinality.isEmpty(), "真实 PGVector 查询应产生标准 VectorStore Observation");
                assertTrue(vectorHighCardinality.stream().noneMatch(tags -> tags.contains("db.vector.query.content")
                        || tags.contains("db.vector.query.filter")
                        || CASES.stream().anyMatch(fixed -> tags.contains(fixed.prompt()))),
                        "向量检索 Span 不得包含原始查询或过滤器内容");
            }
        } finally {
            // schema 由本测试随机生成，只删除本次隔离数据。
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    /** 将已有业务 Trace 降为安全摘要，保留诊断延迟所需的事件与 token 字段。 */
    private CaseReport toReport(String id, AgentRunResult result, long elapsedMs) {
        List<TraceSummary> events = result.trace().stream()
                .map(e -> new TraceSummary(e.step(), e.type().name(), e.name(), e.status(),
                        e.elapsedMs(), e.promptTokens(), e.completionTokens()))
                .toList();
        // 每次逻辑调用仅计首次尝试，重试事件由 tool.retry 单独统计。
        long toolCalls = result.trace().stream().filter(e -> e.type() == TraceRecorder.EventType.TOOL)
                .filter(e -> e.status().endsWith(", attempt=1")).count();
        long knownPrompt = result.trace().stream().filter(e -> e.type() == TraceRecorder.EventType.MODEL)
                .map(TraceRecorder.TraceEvent::promptTokens).filter(v -> v != null && v >= 0)
                .mapToLong(Integer::longValue).sum();
        long knownCompletion = result.trace().stream().filter(e -> e.type() == TraceRecorder.EventType.MODEL)
                .map(TraceRecorder.TraceEvent::completionTokens).filter(v -> v != null && v >= 0)
                .mapToLong(Integer::longValue).sum();
        long unknownUsage = result.trace().stream().filter(e -> e.type() == TraceRecorder.EventType.MODEL)
                .filter(e -> !"EMPTY_ANSWER_RETRY".equals(e.status()))
                .filter(e -> e.promptTokens() == null || e.completionTokens() == null).count();
        return new CaseReport(id, result.status().name(), elapsedMs, result.completedSteps(),
                toolCalls, knownPrompt, knownCompletion, unknownUsage, events);
    }

    /** 将时间线、分位数和 Micrometer 计数保存为不含输入输出正文的 JSON。 */
    private void writeReport(List<CaseReport> cases, MeterRegistry meters,
                             List<ObservationEdge> observationTree,
                             String chatModel, String embeddingModel) throws Exception {
        List<Long> latencies = cases.stream().map(CaseReport::elapsedMs).sorted().toList();
        Map<String, MetricSummary> components = new LinkedHashMap<>();
        for (String name : List.of("agent.latency", "llm.request.latency", "tool.latency",
                "rag.retrieve.latency", "mcp.latency")) {
            var timers = meters.find(name).timers();
            components.put(name, new MetricSummary(timers.stream().mapToLong(Timer::count).sum(),
                    timers.stream().mapToDouble(t -> t.totalTime(TimeUnit.MILLISECONDS)).sum()));
        }
        Map<String, Double> counts = new LinkedHashMap<>();
        for (String name : List.of("agent.requests", "agent.failures", "agent.policy.rejects",
                "tool.calls", "tool.failures", "tool.timeout", "tool.retry", "mcp.calls", "mcp.failure",
                "llm.usage.unknown")) {
            counts.put(name, meters.find(name).counters().stream().mapToDouble(Counter::count).sum());
        }
        counts.put("llm.prompt.tokens", tokenTotal(meters, "llm.prompt.tokens"));
        counts.put("llm.completion.tokens", tokenTotal(meters, "llm.completion.tokens"));
        long failures = cases.stream().filter(c -> !"COMPLETED".equals(c.status())).count();
        double averageSteps = cases.stream().mapToInt(CaseReport::steps).average().orElse(0);
        double averageToolCalls = cases.stream().mapToLong(CaseReport::toolCalls).average().orElse(0);
        long promptTokens = cases.stream().mapToLong(CaseReport::promptTokens).sum();
        long completionTokens = cases.stream().mapToLong(CaseReport::completionTokens).sum();
        long unknownUsageCalls = cases.stream().mapToLong(CaseReport::unknownUsageCalls).sum();
        EvaluationReport report = new EvaluationReport("day13-real-pgvector-ollama",
                OffsetDateTime.now(ZoneOffset.UTC).toString(), chatModel, embeddingModel,
                cases.size(), percentile(latencies, 0.50), percentile(latencies, 0.95),
                averageSteps, averageToolCalls, (double) failures / cases.size(), promptTokens,
                completionTokens, unknownUsageCalls, components, counts, observationTree, cases);
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8);
    }

    /** 同名 Counter 可能有多个安全的低基数标签组合，Eval 汇总全部序列。 */
    private double count(MeterRegistry meters, String name) {
        return meters.find(name).counters().stream().mapToDouble(Counter::count).sum();
    }

    /** Token 是每次模型调用的数值分布，汇总 Summary 的总量而非 Counter。 */
    private double tokenTotal(MeterRegistry meters, String name) {
        return meters.find(name).summaries().stream().mapToDouble(DistributionSummary::totalAmount).sum();
    }

    /** 从实际注入的 ChatModel 选项中获取模型名，防止配置切换后报告仍写旧值。 */
    private String resolveChatModel(ConfigurableApplicationContext context) {
        ChatModel chatModel = context.getBean(ChatModel.class);
        if (chatModel.getOptions() instanceof OllamaChatOptions options) {
            return options.getModel();
        }
        return chatModel.getClass().getSimpleName();
    }

    /** 对固定小样本采用最近秩定义；保留毫秒原值以免虚构测量精度。 */
    private long percentile(List<Long> sorted, double probability) {
        if (sorted.isEmpty()) return 0;
        int index = Math.max(0, (int) Math.ceil(sorted.size() * probability) - 1);
        return sorted.get(index);
    }

    /** 从项目已有的本地配置读取 VM 连接，不将数据库密码写入测试源码或报告。 */
    private JdbcTemplate jdbc() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))
                .forEach(environment.getPropertySources()::addLast);
        DriverManagerDataSource datasource = new DriverManagerDataSource();
        datasource.setUrl(environment.getRequiredProperty("spring.datasource.url"));
        datasource.setUsername(environment.getRequiredProperty("spring.datasource.username"));
        datasource.setPassword(environment.getRequiredProperty("spring.datasource.password"));
        return new JdbcTemplate(datasource);
    }

    /** 固定案例定义，scenario 仅控制本地工具数据。 */
    private record FixedCase(String id, MockScenario scenario, String prompt) { }
    /** 脱敏后的单次运行摘要。 */
    private record CaseReport(String id, String status, long elapsedMs, int steps, long toolCalls,
                              long promptTokens, long completionTokens, long unknownUsageCalls,
                              List<TraceSummary> trace) { }
    /** 业务事件摘要；故意不导出 input/output。 */
    private record TraceSummary(int step, String type, String name, String status, long elapsedMs,
                                Integer promptTokens, Integer completionTokens) { }
    /** 所有相同名字的低基数 tag 时间序列聚合值。 */
    private record MetricSummary(long count, double totalMs) { }
    /** Micrometer Observation 树边；父为空表示请求根。 */
    private record ObservationEdge(String name, String parent) { }
    /** 固定 Eval 最终报告，其中 token 未知次数与已知 token 总数分开。 */
    private record EvaluationReport(String suite, String generatedAt, String chatModel,
                                    String embeddingModel, int totalCases, long p50LatencyMs,
                                    long p95LatencyMs, double averageSteps, double averageToolCalls,
                                    double failureRate, long promptTokens, long completionTokens,
                                    long unknownUsageCalls, Map<String, MetricSummary> componentLatency,
                                    Map<String, Double> counters, List<ObservationEdge> observationTree,
                                    List<CaseReport> cases) { }
}
