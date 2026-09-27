package org.example.ai.eval;

import org.example.ai.SpringAiStudyApplication;
import org.example.ai.diagnosis.DiagnosisReport;
import org.example.ai.diagnosis.DiagnosisStatus;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 PGVector、BGE-M3 与 Ollama 验收 Day14 的诊断报告结构转换。 */
class Day14StructuredOutputIT {
    private static final Path REPORT_PATH = Path.of("target", "eval-results", "day14-structured-output.json");
    private final JsonMapper mapper = JsonMapper.builder().build();

    /** 在隔离 PG schema 中运行固定 E01 与证据不足案例，并始终保存已完成案例结果。 */
    @Test
    void fixedRealPgvectorAndOllamaStructuredOutputAcceptance() throws Exception {
        String schema = "day14_it_" + UUID.randomUUID().toString().replace("-", "");
        Path manifest = Path.of("target", "day14", schema + ".json");
        JdbcTemplate jdbc = jdbc();
        jdbc.execute("CREATE SCHEMA " + schema);
        List<CaseReport> caseReports = new ArrayList<>();
        try {
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SpringAiStudyApplication.class)
                    .web(WebApplicationType.NONE)
                    .run("--spring.profiles.active=local", "--app.ops.data-source=MOCK",
                            "--spring.ai.ollama.chat.temperature=0.0", "--spring.ai.ollama.chat.seed=42",
                            "--spring.ai.vectorstore.pgvector.schema-name=" + schema,
                            "--rag.index.manifest-path=" + manifest)) {
                AgentRunner runner = context.getBean(AgentRunner.class);
                OpsMockDataProvider mockData = context.getBean(OpsMockDataProvider.class);
                AgentEvalCase e01 = AgentEvalCases.all().stream()
                        .filter(evalCase -> "E01".equals(evalCase.id())).findFirst().orElseThrow();
                List<FixedCase> fixedCases = List.of(
                        new FixedCase("D14-E01", e01.scenario(), e01.prompt()),
                        new FixedCase("D14-INSUFFICIENT", MockScenario.E07_UNKNOWN_INCIDENT,
                                "ZXQ-917 FROBNICATOR glyph checksum anomaly 是什么？请基于已有证据判断。"));
                try {
                    for (FixedCase fixed : fixedCases) {
                        String conversationId = fixed.id().toLowerCase();
                        runner.clearMemory(conversationId);
                        mockData.useScenario(fixed.scenario());
                        long started = System.nanoTime();
                        AgentRunResult result = runner.run(fixed.prompt(), conversationId, false);
                        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                        caseReports.add(toReport(fixed.id(), result, elapsedMs));
                        runner.clearMemory(conversationId);
                    }
                } finally {
                    mockData.reset();
                    writeReport(caseReports, resolveChatModel(context),
                            context.getEnvironment().getRequiredProperty("spring.ai.ollama.embedding.model"));
                }

                CaseReport e01Report = find(caseReports, "D14-E01");
                assertEquals("COMPLETED", e01Report.runStatus(), "E01 必须转换为正常完成，详见 " + REPORT_PATH);
                assertEquals(DiagnosisStatus.DIAGNOSED, e01Report.report().status(),
                        "E01 必须返回 DIAGNOSED 报告，详见 " + REPORT_PATH);
                assertTrue(!e01Report.report().hypotheses().isEmpty(), "E01 需要至少一个根因假设");
                assertTrue(e01Report.report().facts().stream()
                                .anyMatch(fact -> e01Report.toolNames().contains(fact.source())),
                        "E01 至少一条事实来源应对应本轮真实 Tool Trace");
                assertTrue(e01Report.report().hypotheses().stream().allMatch(hypothesis ->
                                !hypothesis.evidence().isEmpty() && Double.isFinite(hypothesis.confidence())
                                        && hypothesis.confidence() >= 0 && hypothesis.confidence() <= 1),
                        "E01 假设必须带证据，置信度须处于 0 到 1 之间");

                CaseReport insufficient = find(caseReports, "D14-INSUFFICIENT");
                assertEquals("COMPLETED", insufficient.runStatus(),
                        "证据不足是一份有效诊断报告，详见 " + REPORT_PATH);
                assertEquals(DiagnosisStatus.INSUFFICIENT_EVIDENCE, insufficient.report().status(),
                        "未知事件应明确返回 INSUFFICIENT_EVIDENCE，详见 " + REPORT_PATH);
                assertFalse(insufficient.report().missingInformation().isEmpty(),
                        "证据不足报告应指出待补充信息");

                AgentEvalCaseResult e01Eval = new AgentEvaluator().evaluate(e01, e01Report.runResult(),
                        e01Report.elapsedMs());
                assertTrue(e01Eval.overallPass(),
                        "E01 的工具选择、结构化诊断、实时来源和数值证据评测必须全部通过，详见 " + REPORT_PATH);
            }
        } finally {
            // 每次只清除本方法随机创建的 schema，保护其他测试与用户数据。
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    /**
     * 将一次运行压缩为可核对结构输出和 Trace 证据来源的报告。
     *
     * @param id 固定案例编号
     * @param result Agent 实际运行结果
     * @param elapsedMs 案例耗时，单位为毫秒
     * @return 包含诊断报告、状态和证据来源的可序列化结果
     */
    private CaseReport toReport(String id, AgentRunResult result, long elapsedMs) {
        DiagnosisReport report = result.report();
        List<String> toolNames = result.trace().stream()
                .filter(event -> event.type() == TraceRecorder.EventType.TOOL)
                .map(TraceRecorder.TraceEvent::name).distinct().sorted().toList();
        List<String> ragSources = result.trace().stream()
                .filter(event -> event.type() == TraceRecorder.EventType.RAG)
                .map(TraceRecorder.TraceEvent::name).distinct().sorted().toList();
        return new CaseReport(id, result.status().name(), result.completedSteps(), elapsedMs,
                toolNames, ragSources, report, result);
    }

    /**
     * 将已完成案例报告写入固定目标路径，即使后续质量断言失败也保留结果。
     *
     * @param cases 已完成或已失败案例的固定结构结果
     * @param chatModel 实际使用的聊天模型名称
     * @param embeddingModel 实际使用的向量模型名称
     * @throws Exception 当目录创建或 JSON 文件写入失败时抛出
     */
    private void writeReport(List<CaseReport> cases, String chatModel, String embeddingModel) throws Exception {
        Files.createDirectories(REPORT_PATH.getParent());
        EvaluationReport report = new EvaluationReport("day14-structured-output",
                OffsetDateTime.now(ZoneOffset.UTC).toString(), chatModel, embeddingModel,
                List.copyOf(cases));
        Files.writeString(REPORT_PATH,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
    }

    /**
     * 从报告集合中查找固定案例，缺失时给出清晰失败。
     *
     * @param reports 已收集的案例报告列表
     * @param id 待查找的案例编号
     * @return 与编号匹配的案例报告
     */
    private CaseReport find(List<CaseReport> reports, String id) {
        return reports.stream().filter(report -> id.equals(report.id())).findFirst().orElseThrow();
    }

    /**
     * 从本地 application.yaml 读取数据库连接，避免在测试源码中保存账号信息。
     *
     * @return 使用本地配置的 JDBC 模板
     * @throws Exception 当配置读取或数据源创建失败时抛出
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

    /**
     * 读取正在使用的 Ollama 模型名，确保报告能反映环境配置。
     *
     * @param context 当前真实 Spring 应用上下文
     * @return 当前 ChatModel 实际配置的模型名称
     */
    private String resolveChatModel(ConfigurableApplicationContext context) {
        ChatModel chatModel = context.getBean(ChatModel.class);
        return chatModel.getOptions() instanceof OllamaChatOptions options
                ? options.getModel() : chatModel.getClass().getSimpleName();
    }

    /** 一个固定提示和本地工具数据场景。
     *
     * @param id 固定场景编号
     * @param scenario 本地工具 Mock 场景
     * @param prompt 发送给真实模型的固定提示
     */
    private record FixedCase(String id, MockScenario scenario, String prompt) { }

    /** 一次执行的已解析诊断报告和用于证据审计的来源。
     *
     * @param id 固定案例编号
     * @param runStatus Agent 执行状态
     * @param completedSteps 实际执行步数
     * @param elapsedMs 请求耗时，单位为毫秒
     * @param toolNames 真实执行的工具名称集合
     * @param ragSources 真实检索到的知识来源集合
     * @param report 模型 JSON 转换后的诊断报告
     * @param runResult 完整 Agent 结果，保留 answer 和 Trace 供复核
     */
    private record CaseReport(String id, String runStatus, int completedSteps, long elapsedMs,
                              List<String> toolNames, List<String> ragSources,
                              DiagnosisReport report, AgentRunResult runResult) { }

    /** 本次真实环境运行的信息及案例结构化结果。
     *
     * @param suite 固定评测套件名称
     * @param generatedAt 报告生成时间，UTC
     * @param chatModel 实际使用的聊天模型名称
     * @param embeddingModel 实际使用的向量模型名称
     * @param cases 所有案例的结构化结果
     */
    private record EvaluationReport(String suite, String generatedAt, String chatModel,
                                    String embeddingModel, List<CaseReport> cases) { }
}
