package org.example.ai.eval;

import org.example.ai.SpringAiStudyApplication;
import org.example.ai.day20.AdvisorAgentRunner;
import org.example.ai.day20.ManualAgentRunner;
import org.example.ai.harness.AgentRunResult;
import org.example.ai.harness.AgentRunner;
import org.example.ai.harness.ExecutionPolicy;
import org.example.ai.harness.TraceRecorder;
import org.example.ai.rag.KnowledgeRetriever;
import org.example.ai.tool.AgentToolProvider;
import org.example.ai.tool.mock.OpsMockDataProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.SpringVersion;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Day20 的真实环境 A/B 实验：同一模型、检索器和固定工具数据分别执行 E01 至 E10。
 * 使用本地虚拟机 PostgreSQL 独立随机 schema，向量与聊天均调用真实服务。
 * 保存 setup 和正式轮次的完整 AgentRunResult，每次完成一组案例后立即落盘。
 */
class Day20LoopComparisonIT {
    /** 固定报告位置，断言失败时保留已完成案例和两组各自的失败评分。 */
    private static final Path REPORT_PATH = Path.of("target", "eval-results", "day20-loop-comparison.json");
    /** JSON 序列化器，采用项目现有 Jackson 3 API。 */
    private final JsonMapper mapper = JsonMapper.builder().build();

    /**
     * 运行全部 A/B 案例并检查每个基线已通过维度是否退化，同时强制执行安全与隔离边界。
     * @throws Exception 当数据库连接、应用启动或报告持久化失败时抛出
     */
    @Test
    void compareManualAndAdvisorOnRealPgvector() throws Exception {
        String schema = "day20_it_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate jdbc = jdbc();
        Map<String, Object> environment = new LinkedHashMap<>();
        List<Comparison> comparisons = new ArrayList<>();
        environment.put("javaVersion", System.getProperty("java.version"));
        environment.put("springBootVersion", SpringBootVersion.getVersion());
        environment.put("springVersion", SpringVersion.getVersion());
        environment.put("springAiVersion", "2.0.1");
        environment.put("postgresqlVersion", jdbc.queryForObject("SELECT version()", String.class));
        environment.put("pgvectorVersion", jdbc.queryForObject(
                "SELECT extversion FROM pg_extension WHERE extname = 'vector'", String.class));
        environment.put("schema", schema);
        environment.put("toolDataSource", "OpsMockDataProvider: fixed E01-E10 scenarios");
        environment.put("observationDifference", "Manual 使用现有上下文 AgentTelemetry；Advisor 仅记录业务 Trace，延迟包含该观测差异");
        environment.put("temperature", 0.0);
        environment.put("seed", 42);
        writeReport(environment, comparisons);
        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SpringAiStudyApplication.class)
                    .web(WebApplicationType.NONE)
                    .run("--spring.profiles.active=local", "--app.ops.data-source=MOCK",
                            "--spring.ai.ollama.chat.temperature=0.0", "--spring.ai.ollama.chat.seed=42",
                            "--spring.ai.vectorstore.pgvector.schema-name=" + schema,
                            "--rag.index.manifest-path=target/day20/" + schema + ".json")) {
                collectConfiguration(context, environment);
                writeReport(environment, comparisons);
                ManualAgentRunner manual = new ManualAgentRunner(context.getBean(AgentRunner.class));
                OpsMockDataProvider data = context.getBean(OpsMockDataProvider.class);
                // 两组共享模型和数据源，但 Advisor 使用自己独立的记忆容器，避免跨组污染。
                try (AdvisorAgentRunner advisor = new AdvisorAgentRunner(context.getBean(ToolCallingManager.class),
                        context.getBean(AgentToolProvider.class), context.getBean(ChatModel.class),
                        context.getBean(ExecutionPolicy.class), context.getBean(KnowledgeRetriever.class),
                        MessageWindowChatMemory.builder().maxMessages(20).build())) {
                    try {
                        for (AgentEvalCase evalCase : AgentEvalCases.all()) {
                            RunReport baseline = execute(evalCase, "manual", manual::run, manual::clearMemory, data);
                            // 先保留基线，防止 Advisor 抛出非受控异常时丢失该案例基线证据。
                            comparisons.add(new Comparison(evalCase.id(), baseline, null, Map.of(), List.of()));
                            writeReport(environment, comparisons);
                            RunReport candidate = execute(evalCase, "advisor", advisor::run, advisor::clearMemory, data);
                            Map<String, DimensionDifference> differences = differences(baseline.score(), candidate.score());
                            List<String> regressions = differences.entrySet().stream()
                                    .filter(entry -> entry.getValue().regressed()).map(Map.Entry::getKey).toList();
                            comparisons.set(comparisons.size() - 1,
                                    new Comparison(evalCase.id(), baseline, candidate, differences, regressions));
                            writeReport(environment, comparisons);
                        }
                    } finally {
                        data.reset();
                        writeReport(environment, comparisons);
                    }
                }
                assertAcceptance(comparisons);
            }
        } finally {
            // schema 名仅由固定前缀和随机十六进制生成；清理范围限于本次实验创建的数据。
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    /**
     * 运行某一组的单个案例，严格使用原案例的 setupSameConversation 与记忆启用规则。
     * @param evalCase 原始案例定义
     * @param variant 执行组名称，参与会话标识以避免跨组共享
     * @param invocation 调用指定执行器的函数
     * @param clearMemory 清除指定执行器会话的函数
     * @param data 同一工具数据提供器，每个案例重新选择固定场景
     * @return setup 与正式结果、正式评分和真实工具尝试数
     */
    private RunReport execute(AgentEvalCase evalCase, String variant, Invocation invocation,
                              Consumer<String> clearMemory, OpsMockDataProvider data) {
        String conversation = "d20-" + variant + "-" + evalCase.id().toLowerCase();
        String setupConversation = evalCase.setupSameConversation() ? conversation : conversation + "-setup";
        clearMemory.accept(conversation);
        clearMemory.accept(setupConversation);
        data.useScenario(evalCase.scenario());
        AgentRunResult setup = null;
        long setupLatencyMs = 0;
        try {
            if (evalCase.setupPrompt() != null) {
                long setupStarted = System.nanoTime();
                setup = invocation.run(evalCase.setupPrompt(), setupConversation, true);
                setupLatencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - setupStarted);
            }
            long started = System.nanoTime();
            AgentRunResult main = invocation.run(evalCase.prompt(), conversation, evalCase.setupPrompt() != null);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            AgentEvalCaseResult score = new AgentEvaluator().evaluate(evalCase, main, elapsedMs);
            return new RunReport(setup, setupLatencyMs, main, score, toolAttempts(main));
        } finally {
            clearMemory.accept(conversation);
            clearMemory.accept(setupConversation);
        }
    }

    /**
     * 逐一检查基线已经通过的评分维度，允许基线失败原样保存在报告中。
     * @param comparisons 已完成的十组 A/B 案例
     */
    private void assertAcceptance(List<Comparison> comparisons) {
        List<Executable> checks = new ArrayList<>();
        checks.add(() -> assertEquals(10, comparisons.size(), "E01-E10 必须全部执行"));
        for (Comparison comparison : comparisons) {
            checks.add(() -> assertNotNull(comparison.advisor(), comparison.id() + " 缺少 Advisor 结果"));
            checks.add(() -> assertTrue(comparison.regressions().isEmpty(),
                    comparison.id() + " 基线已通过维度退化: " + comparison.regressions() + "; " + REPORT_PATH));
            if ("E08".equals(comparison.id())) {
                for (RunReport run : List.of(comparison.manual(), comparison.advisor())) {
                    checks.add(() -> assertEquals(AgentRunResult.RunStatus.POLICY_REJECTED, run.main().status(),
                            "E08 两组均必须拒绝越权调用"));
                    checks.add(() -> assertEquals(0, run.toolAttempts(), "E08 不得实际执行工具"));
                }
            }
            if ("E10".equals(comparison.id())) {
                for (RunReport run : List.of(comparison.manual(), comparison.advisor())) {
                    checks.add(() -> assertTrue(run.score().mainJudgmentPass(), "E10 两组均必须保持会话隔离"));
                    checks.add(() -> assertEquals(0, run.toolAttempts(), "E10 不得根据另一会话调用工具"));
                }
            }
        }
        assertAll("Day20 A/B 案例维度不退化与硬边界", checks);
    }

    /**
     * 将评分转换为所有需逐案例比较的布尔维度。
     * @param score 一组执行器的正式评分
     * @return 保持稳定字段顺序的维度与通过值
     */
    private Map<String, Boolean> dimensions(AgentEvalCaseResult score) {
        Map<String, Boolean> values = new LinkedHashMap<>();
        values.put("mainJudgmentPass", score.mainJudgmentPass());
        values.put("statusPass", score.statusPass());
        values.put("toolSelectionPass", score.toolSelectionPass());
        values.put("stepPass", score.stepPass());
        values.put("evidenceFaithfulnessPass", score.evidenceFaithfulnessPass());
        values.put("sourceCitationPass", score.sourceCitationPass());
        values.put("structuredOutputPass", score.structuredOutputPass());
        values.put("ragExpectationPass", score.ragExpectationPass());
        values.put("exceptionHandlingPass", score.exceptionHandlingPass());
        values.put("overallPass", score.overallPass());
        return values;
    }

    /**
     * 保存同一案例各评分维度的原始值和是否退化。
     * @param baseline Manual 原始评分
     * @param candidate Advisor 原始评分
     * @return 每个维度的两组通过值与退化判断
     */
    private Map<String, DimensionDifference> differences(AgentEvalCaseResult baseline, AgentEvalCaseResult candidate) {
        Map<String, Boolean> manual = dimensions(baseline);
        Map<String, Boolean> advisor = dimensions(candidate);
        Map<String, DimensionDifference> result = new LinkedHashMap<>();
        manual.forEach((name, pass) -> result.put(name,
                new DimensionDifference(pass, advisor.get(name), pass && !advisor.get(name))));
        return result;
    }

    /**
     * 从实际 TOOL 事件计数，包括重试尝试；工具名称去重数不能代表真实调用成本。
     * @param result 完整执行结果
     * @return 本次 TOOL 轨迹事件数量
     */
    private long toolAttempts(AgentRunResult result) {
        return result.trace().stream().filter(event -> event.type() == TraceRecorder.EventType.TOOL).count();
    }

    /**
     * 汇总正式轮次的质量、调用成本与 P95；setup 成本与完整轨迹另外保存在每案例报告。
     * @param runs 已完成的某一组正式执行报告
     * @return 可序列化汇总，空集合时各计数及指标为零
     */
    private Map<String, Object> summary(List<RunReport> runs) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cases", runs.size());
        result.put("overallPass", runs.stream().filter(run -> run.score().overallPass()).count());
        result.put("mainPass", runs.stream().filter(run -> run.score().mainJudgmentPass()).count());
        result.put("avgSteps", runs.stream().mapToInt(run -> run.main().completedSteps()).average().orElse(0));
        result.put("avgToolAttempts", runs.stream().mapToLong(RunReport::toolAttempts).average().orElse(0));
        result.put("totalToolAttempts", runs.stream().mapToLong(RunReport::toolAttempts).sum());
        result.put("promptTokens", runs.stream().mapToLong(run -> run.score().promptTokens()).sum());
        result.put("completionTokens", runs.stream().mapToLong(run -> run.score().completionTokens()).sum());
        result.put("totalTokens", runs.stream().mapToLong(run -> run.score().totalTokens()).sum());
        List<Long> latencies = runs.stream().map(run -> run.score().latencyMs()).sorted().toList();
        result.put("p95LatencyMs", latencies.isEmpty() ? 0 : latencies.get((int) Math.ceil(latencies.size() * 0.95) - 1));
        result.put("tokenAccounting", "真实 MODEL Trace usage 求和；缺失 usage 不估算");
        return result;
    }

    /**
     * 每完成案例立即保存原始比较及当前汇总，避免最后一个断言失败丢失证据。
     * @param environment 可公开的环境版本和模型检索配置，不含数据库凭据
     * @param comparisons 当前已完成案例，可包含仅完成基线的案例
     * @throws Exception 当目录创建或 JSON 写入失败时抛出
     */
    private void writeReport(Map<String, Object> environment, List<Comparison> comparisons) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("suite", "day20-loop-comparison");
        report.put("generatedAt", OffsetDateTime.now().toString());
        report.put("environment", environment);
        report.put("manualSummary", summary(comparisons.stream().map(Comparison::manual).toList()));
        report.put("advisorSummary", summary(comparisons.stream().map(Comparison::advisor).filter(java.util.Objects::nonNull).toList()));
        report.put("comparisons", comparisons);
        Files.createDirectories(REPORT_PATH.getParent());
        Files.writeString(REPORT_PATH, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8);
    }

    /**
     * 读取上下文中实际使用的模型选项和检索配置。
     * @param context 已启动的真实应用上下文
     * @param environment 用于保存公开元数据的可变映射
     */
    private void collectConfiguration(ConfigurableApplicationContext context, Map<String, Object> environment) {
        ChatModel model = context.getBean(ChatModel.class);
        if (model.getOptions() instanceof OllamaChatOptions options) {
            environment.put("chatModel", options.getModel());
            environment.put("actualTemperature", options.getTemperature());
            environment.put("actualSeed", options.getSeed());
        }
        for (String key : List.of("spring.ai.ollama.embedding.model", "spring.ai.ollama.chat.num-ctx",
                "spring.ai.vectorstore.pgvector.dimensions", "spring.ai.vectorstore.pgvector.index-type",
                "spring.ai.vectorstore.pgvector.distance-type", "rag.retrieval.top-k", "rag.retrieval.threshold",
                "rag.retrieval.mode")) {
            environment.put(key, context.getEnvironment().getProperty(key));
        }
    }

    /**
     * 从项目配置解析本地虚拟机 JDBC 连接，凭据仅用于数据源创建。
     * @return 使用项目 PostgreSQL 配置的 JDBC 模板
     * @throws Exception 当 YAML 配置读取失败时抛出
     */
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

    /** 供两种执行器方法引用共用的测试调用契约。 */
    @FunctionalInterface
    private interface Invocation {
        /**
         * 调用指定执行器。
         * @param prompt 用户问题
         * @param conversationId 独立会话标识
         * @param memoryEnabled 是否读取和保存成功轮次
         * @return 保留报告与轨迹的原始执行结果
         */
        AgentRunResult run(String prompt, String conversationId, boolean memoryEnabled);
    }

    /**
     * 一组执行器的完整证据。
     * @param setup 多轮第一轮原始结果；单轮案例为 null
     * @param setupLatencyMs 第一轮耗时，毫秒
     * @param main 正式轮次完整结果，包含所有 Trace
     * @param score 使用既有确定性判定器产生的原始评分
     * @param toolAttempts 正式轮次包含重试的实际工具尝试数
     */
    private record RunReport(AgentRunResult setup, long setupLatencyMs, AgentRunResult main,
                             AgentEvalCaseResult score, long toolAttempts) { }

    /**
     * 同一评分维度的 A/B 原始值。
     * @param manual 基线是否通过
     * @param advisor 候选是否通过
     * @param regressed 基线通过且候选失败时为 true
     */
    private record DimensionDifference(boolean manual, boolean advisor, boolean regressed) { }

    /**
     * 同一固定案例的全部比较证据。
     * @param id E01 至 E10 案例编号
     * @param manual 基线执行与评分，包含基线自身失败
     * @param advisor 候选执行与评分；候选未完成时为 null
     * @param differences 逐项维度差异
     * @param regressions 所有基线已通过却在候选失败的维度名称
     */
    private record Comparison(String id, RunReport manual, RunReport advisor,
                              Map<String, DimensionDifference> differences, List<String> regressions) { }
}
