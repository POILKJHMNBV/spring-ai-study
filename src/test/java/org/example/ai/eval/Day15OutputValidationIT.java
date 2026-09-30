package org.example.ai.eval;

import org.example.ai.SpringAiStudyApplication;
import org.example.ai.diagnosis.DiagnosisReport;
import org.example.ai.diagnosis.DiagnosisStatus;
import org.example.ai.harness.AgentRunResult;
import org.example.ai.harness.AgentRunner;
import org.example.ai.harness.ExecutionPolicy;
import org.example.ai.harness.TraceRecorder;
import org.example.ai.rag.KnowledgeRetriever;
import org.example.ai.rag.RetrievedChunk;
import org.example.ai.tool.AgentToolProvider;
import org.example.ai.tool.mock.MockScenario;
import org.example.ai.tool.mock.OpsMockDataProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.mockito.Mockito;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 在真实 PostgreSQL/pgvector/BGE 环境中分别记录可重复脚本修复与真实 Ollama 验收。 */
class Day15OutputValidationIT {
    private static final Path REPORT_PATH = Path.of("target", "eval-results", "day15-output-validation.json");
    private final JsonMapper mapper = JsonMapper.builder().build();

    /** 创建随机隔离 schema，执行脚本故障和真实 Ollama 案例，最后保存结果并清理 schema。 */
    @Test
    void scriptedRepairAndRealOllamaUseIsolatedPgvectorSchema() throws Exception {
        String schema = "day15_it_" + UUID.randomUUID().toString().replace("-", "");
        Path manifest = Path.of("target", "day15", schema + ".json");
        JdbcTemplate jdbc = jdbc();
        jdbc.execute("CREATE SCHEMA " + schema);
        List<CaseReport> cases = new ArrayList<>();
        String chatModelName = "unavailable";
        String embeddingModelName = "unavailable";
        String postgresVersion = "unavailable";
        String vectorVersion = "unavailable";
        try {
            postgresVersion = jdbc.queryForObject("SHOW server_version", String.class);
            vectorVersion = jdbc.queryForObject(
                    "SELECT extversion FROM pg_extension WHERE extname = 'vector'", String.class);
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SpringAiStudyApplication.class)
                    .web(WebApplicationType.NONE)
                    .run("--spring.profiles.active=local", "--app.ops.data-source=MOCK",
                            "--spring.ai.ollama.chat.temperature=0.0", "--spring.ai.ollama.chat.seed=42",
                            "--spring.ai.vectorstore.pgvector.schema-name=" + schema,
                            "--rag.index.manifest-path=" + manifest)) {
                ChatModel ollama = context.getBean(ChatModel.class);
                chatModelName = resolveChatModel(ollama);
                embeddingModelName = context.getEnvironment()
                        .getRequiredProperty("spring.ai.ollama.embedding.model");
                KnowledgeRetriever retriever = context.getBean(KnowledgeRetriever.class);
                AgentToolProvider toolProvider = context.getBean(AgentToolProvider.class);
                ExecutionPolicy policy = context.getBean(ExecutionPolicy.class);
                ChatMemory memory = context.getBean(ChatMemory.class);
                OpsMockDataProvider mockData = context.getBean(OpsMockDataProvider.class);
                AgentRunner scriptedRunner = null;
                AgentRunner realRunner = context.getBean(AgentRunner.class);
                try {
                    String scriptPrompt = "payment-service 的数据库 P99 暴涨，连接池接近满，请基于当前证据判断。";
                    RetrievedChunk reference = retriever.retrieve(scriptPrompt)
                            .stream().findFirst().orElseThrow(() ->
                                    new AssertionError("真实 pgvector/BGE 检索未返回数据库知识块"));
                    assertFalse(reference.text().isBlank(), "真实检索块正文不能为空");
                    String quote = reference.text().substring(0, Math.min(160, reference.text().length())).strip();
                    ScriptFixture successScript = scriptedRunner(List.of(
                            response(knowledgeReport("伪造的知识来源", quote)),
                            response(knowledgeReport(reference.source(), quote))),
                            toolProvider, policy, retriever, memory);
                    scriptedRunner = successScript.runner();
                    runScriptCase(cases, "SCRIPTED_FAKE_SOURCE_THEN_RECOVERED", successScript,
                            scriptPrompt, "day15-script-recovery", memory);
                    CaseReport recovered = find(cases, "SCRIPTED_FAKE_SOURCE_THEN_RECOVERED");
                    assertEquals("COMPLETED", recovered.runStatus(), "脚本模型必须完成一次受限修复");
                    assertEquals(DiagnosisStatus.INSUFFICIENT_EVIDENCE, recovered.report().status());
                    assertEquals(2, recovered.modelCalls());
                    assertEquals(1, recovered.report().facts().size());
                    assertTrue(recovered.report().facts().get(0).statement().startsWith("知识引用："),
                            "修复报告应保留逐字知识引用");
                    scriptedRunner.shutdownToolExecutor();

                    ScriptFixture failureScript = scriptedRunner(List.of(
                            response("脚本注入：非法输出一"), response("脚本注入：非法输出二")),
                            toolProvider, policy, retriever, memory);
                    scriptedRunner = failureScript.runner();
                    runScriptCase(cases, "SCRIPTED_CONSECUTIVE_INVALID", failureScript,
                            "请基于现有资料分析 ZXQ-918 的状态。", "day15-script-failure", memory);
                    CaseReport rejected = find(cases, "SCRIPTED_CONSECUTIVE_INVALID");
                    assertEquals("FAILED", rejected.runStatus(), "连续非法输出应在唯一修复失败后终止");
                    assertEquals(2, rejected.modelCalls());
                    assertFalse(rejected.memorySaved(), "失败报告不得写入会话记忆");
                    scriptedRunner.shutdownToolExecutor();
                    scriptedRunner = null;

                    // 首次输出由脚本故意破坏；唯一修复委托真实 Ollama，单独验证无思考的恢复选项。
                    // 该场景明确标记为故障注入，不能与全程真实模型场景混为一谈。
                    AtomicInteger repairCalls = new AtomicInteger();
                    ChatModel repairModel = Mockito.mock(ChatModel.class);
                    when(repairModel.getOptions()).thenReturn(ollama.getOptions());
                    when(repairModel.call(any(Prompt.class))).thenAnswer(invocation ->
                            repairCalls.getAndIncrement() == 0 ? response("故障注入：非 JSON 报告")
                                    : ollama.call(invocation.getArgument(0, Prompt.class)));
                    scriptedRunner = new AgentRunner(ToolCallingManager.builder().build(), toolProvider,
                            repairModel, policy, retriever, memory);
                    ScriptFixture injectedRepair = new ScriptFixture(scriptedRunner, repairCalls);
                    runScriptCase(cases, "INJECTED_INVALID_THEN_REAL_OLLAMA_REPAIR", injectedRepair,
                            "未知事件 ZXQ-917 尚无可查实体，请只依据已有证据说明还需要什么信息。",
                            "day15-real-repair", memory);
                    CaseReport realRepair = find(cases, "INJECTED_INVALID_THEN_REAL_OLLAMA_REPAIR");
                    scriptedRunner.shutdownToolExecutor();
                    scriptedRunner = null;

                    AgentEvalCase e01 = AgentEvalCases.all().stream()
                            .filter(evalCase -> "E01".equals(evalCase.id())).findFirst().orElseThrow();
                    assertAll("真实 Ollama 场景逐项运行并记录结果",
                            () -> {
                                assertEquals("COMPLETED", realRepair.runStatus(),
                                        "真实模型必须在唯一一次修复中恢复非法格式");
                                assertEquals(2, realRepair.modelCalls());
                                assertEquals(DiagnosisStatus.INSUFFICIENT_EVIDENCE, realRepair.report().status());
                                assertTrue(realRepair.validationTrace().stream().anyMatch(event ->
                                        "REPAIR".equals(event.name()) && "SUCCEEDED".equals(event.status())));
                            },
                            () -> {
                                String realConversation = "day15-ollama-e07";
                                realRunner.clearMemory(realConversation);
                                mockData.useScenario(MockScenario.E07_UNKNOWN_INCIDENT);
                                long started = System.nanoTime();
                                AgentRunResult realResult = realRunner.run(
                                        "ZXQ-917 FROBNICATOR glyph checksum anomaly 是什么？请依据当前工具和知识证据判断。",
                                        realConversation, false);
                                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                                cases.add(toReport("REAL_OLLAMA_E07", realResult, elapsedMs, -1, false));
                                assertAll("REAL_OLLAMA_E07",
                                        () -> assertEquals(AgentRunResult.RunStatus.COMPLETED, realResult.status(),
                                                "真实 Ollama E07 场景必须生成结构化报告，详见 " + REPORT_PATH),
                                        () -> assertEquals(DiagnosisStatus.INSUFFICIENT_EVIDENCE,
                                                realResult.report().status(),
                                                "未知事件不得伪造诊断，详见 " + REPORT_PATH),
                                        () -> assertFalse(realResult.report().missingInformation().isEmpty(),
                                                "真实证据不足报告应指出仍缺少的信息"));
                            },
                            () -> {
                                String diagnosisConversation = "day15-ollama-e01";
                                realRunner.clearMemory(diagnosisConversation);
                                mockData.useScenario(e01.scenario());
                                long started = System.nanoTime();
                                AgentRunResult diagnosisResult = realRunner.run(
                                        e01.prompt(), diagnosisConversation, false);
                                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                                cases.add(toReport("REAL_OLLAMA_E01", diagnosisResult, elapsedMs, -1, false));
                                List<String> successfulTools = diagnosisResult.trace().stream()
                                        .filter(event -> event.type() == TraceRecorder.EventType.TOOL
                                                && event.status().startsWith("SUCCESS"))
                                        .map(TraceRecorder.TraceEvent::name).distinct().toList();
                                assertAll("REAL_OLLAMA_E01",
                                        () -> assertEquals(AgentRunResult.RunStatus.COMPLETED,
                                                diagnosisResult.status(),
                                                "真实 Ollama E01 工具诊断必须完成，详见 " + REPORT_PATH),
                                        () -> assertEquals(DiagnosisStatus.DIAGNOSED,
                                                diagnosisResult.report().status(),
                                                "真实 E01 应基于工具证据完成诊断，详见 " + REPORT_PATH),
                                        () -> assertTrue(diagnosisResult.report().facts().stream()
                                                        .anyMatch(fact -> successfulTools.contains(fact.source())),
                                                "真实 E01 至少一条诊断事实应引用本轮成功工具"),
                                        () -> assertTrue(diagnosisResult.report().hypotheses().stream()
                                                        .allMatch(hypothesis -> hypothesis.confidence() >= 0.0
                                                                && hypothesis.confidence() <= 1.0
                                                                && !hypothesis.evidence().isEmpty()),
                                                "真实 E01 假设应通过置信度和事实引用校验"));
                            });
                } finally {
                    if (scriptedRunner != null) {
                        scriptedRunner.shutdownToolExecutor();
                    }
                    realRunner.clearMemory("day15-ollama-e07");
                    realRunner.clearMemory("day15-ollama-e01");
                    mockData.reset();
                }
            }
        } finally {
            // 只删除本测试随机创建的 schema，避免影响其他测试和用户数据。
            try {
                jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
            } finally {
                Files.deleteIfExists(manifest);
                writeReport(cases, schema, postgresVersion, vectorVersion,
                        chatModelName, embeddingModelName);
            }
        }
    }

    /** 运行一次脚本模型案例，并记录调用数、真实 Agent Trace 和记忆写入结果。
     * @param cases 当前评测报告中的案例集合
     * @param id 固定案例编号
     * @param fixture 固定模型响应与 AgentRunner
     * @param prompt 本次排查问题
     * @param conversationId 本案例独立会话 ID
     * @param memory 当前上下文提供的会话记忆
     */
    private void runScriptCase(List<CaseReport> cases, String id, ScriptFixture fixture, String prompt,
                               String conversationId, ChatMemory memory) {
        memory.clear(conversationId);
        long started = System.nanoTime();
        AgentRunResult result = fixture.runner().run(prompt, conversationId, true);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        boolean memorySaved = !memory.get(conversationId).isEmpty();
        cases.add(toReport(id, result, elapsedMs, fixture.modelCalls().get(), memorySaved));
        memory.clear(conversationId);
    }

    /** 把指定响应序列装入 ChatModel 桩，同时保留真实 retriever、工具发现和 AgentRunner。
     * @param responses 按调用顺序排列的模型响应
     * @param tools 当前上下文中的工具发现器
     * @param policy 当前上下文中的工具执行策略
     * @param retriever 连接到本次隔离 schema 的真实检索器
     * @param memory 当前上下文中的会话记忆
     * @return 脚本模型对应的 AgentRunner 和模型调用计数
     */
    private ScriptFixture scriptedRunner(List<ChatResponse> responses, AgentToolProvider tools,
                                         ExecutionPolicy policy, KnowledgeRetriever retriever, ChatMemory memory) {
        AtomicInteger modelCalls = new AtomicInteger();
        ChatModel model = Mockito.mock(ChatModel.class);
        when(model.getOptions()).thenReturn(OllamaChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            int index = modelCalls.getAndIncrement();
            return responses.get(Math.min(index, responses.size() - 1));
        });
        return new ScriptFixture(new AgentRunner(ToolCallingManager.builder().build(), tools, model,
                policy, retriever, memory), modelCalls);
    }

    /** 创建只返回固定正文的聊天响应。
     * @param text 固定 Assistant 正文
     * @return 单条生成结果的聊天响应
     */
    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** 用真实检索来源和逐字引用构造证据不足报告，可选伪造来源用于故障注入。
     * @param source 报告声称的知识来源
     * @param quote 从真实 chunk 正文摘取的连续文本
     * @return 结构正确并包含一条知识事实的证据不足 JSON
     * @throws Exception 当 JSON 字符串转义失败时抛出
     */
    private String knowledgeReport(String source, String quote) throws Exception {
        return "{\"status\":\"INSUFFICIENT_EVIDENCE\",\"summary\":\"脚本模型确认当前证据不足\","
                + "\"facts\":[{\"statement\":" + mapper.writeValueAsString("知识引用：" + quote)
                + ",\"source\":" + mapper.writeValueAsString(source)
                + "}],\"hypotheses\":[],\"nextActions\":[],"
                + "\"missingInformation\":[\"需要最近五分钟的错误日志\"]}";
    }

    /** 将一次 Agent 运行压缩为可复核的评测记录。
     * @param id 评测案例编号
     * @param result Agent 的真实运行结果
     * @param elapsedMs 案例耗时毫秒数
     * @param modelCalls 脚本模型调用次数，真实模型场景记为负数
     * @param memorySaved 是否保存成功轮次到记忆
     * @return 包含报告和验证 Trace 的序列化记录
     */
    private CaseReport toReport(String id, AgentRunResult result, long elapsedMs, int modelCalls,
                                boolean memorySaved) {
        DiagnosisReport report = result.report();
        List<String> toolNames = result.trace().stream()
                .filter(event -> event.type() == TraceRecorder.EventType.TOOL)
                .map(TraceRecorder.TraceEvent::name).distinct().sorted().toList();
        List<TraceRecorder.TraceEvent> validationTrace = result.trace().stream()
                .filter(event -> event.type() == TraceRecorder.EventType.VALIDATION).toList();
        return new CaseReport(id, result.status().name(), result.completedSteps(), elapsedMs,
                modelCalls, memorySaved, toolNames, validationTrace, report, result);
    }

    /** 即使后续质量断言失败，也将已经执行的案例结果写入固定 JSON 路径。
     * @param cases 已运行的案例记录
     * @param schema 本次随机隔离 schema 名称
     * @param postgresVersion PostgreSQL 版本
     * @param vectorVersion pgvector 扩展版本
     * @param chatModel 实际聊天模型名称
     * @param embeddingModel 实际向量模型名称
     * @throws Exception 当创建目录或写入 JSON 失败时抛出
     */
    private void writeReport(List<CaseReport> cases, String schema, String postgresVersion,
                             String vectorVersion, String chatModel, String embeddingModel) throws Exception {
        Files.createDirectories(REPORT_PATH.getParent());
        EvaluationReport report = new EvaluationReport("day15-output-validation",
                OffsetDateTime.now(ZoneOffset.UTC).toString(), schema, postgresVersion, vectorVersion,
                chatModel, embeddingModel, List.copyOf(cases));
        Files.writeString(REPORT_PATH,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
    }

    /** 按固定编号查找脚本案例并在缺失时给出失败。
     * @param reports 当前案例报告集合
     * @param id 要查找的案例编号
     * @return 与编号匹配的案例报告
     */
    private CaseReport find(List<CaseReport> reports, String id) {
        return reports.stream().filter(report -> id.equals(report.id())).findFirst().orElseThrow();
    }

    /** 使用本地 application.yaml 的 JDBC 设置连接已验证的 PostgreSQL 实例。
     * @return 使用项目本地 PostgreSQL 设置的 JDBC 模板
     * @throws Exception 当 YAML 配置读取或解析失败时抛出
     */
    private JdbcTemplate jdbc() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))
                .forEach(environment.getPropertySources()::addLast);
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(environment.getRequiredProperty("spring.datasource.url"));
        dataSource.setUsername(environment.getRequiredProperty("spring.datasource.username"));
        dataSource.setPassword(environment.getRequiredProperty("spring.datasource.password"));
        return new JdbcTemplate(dataSource);
    }

    /** 记录当前 ChatModel 实际使用的 Ollama 模型名称。
     * @param model 当前上下文中的聊天模型
     * @return Ollama 模型配置名称或实现类名称
     */
    private String resolveChatModel(ChatModel model) {
        return model.getOptions() instanceof OllamaChatOptions options
                ? options.getModel() : model.getClass().getSimpleName();
    }

    /** 单个脚本案例运行时使用的 AgentRunner 与模型调用计数。
     * @param runner 使用真实检索和工具发现的 AgentRunner
     * @param modelCalls 当前脚本模型调用数
     */
    private record ScriptFixture(AgentRunner runner, AtomicInteger modelCalls) { }

    /** 一次案例的结构化结果、修复 Trace 和记忆边界。
     * @param id 固定案例编号
     * @param runStatus Agent 执行状态
     * @param completedSteps Agent 完成或失败前的步数
     * @param elapsedMs 本次执行耗时毫秒数
     * @param modelCalls 模型调用数或真实模型的未知标记
     * @param memorySaved 成功结果是否写入会话记忆
     * @param toolNames Trace 中实际成功或失败的工具名称
     * @param validationTrace 输出校验与修复事件
     * @param report 最终结构化诊断报告
     * @param runResult 完整 Agent 运行结果供审计
     */
    private record CaseReport(String id, String runStatus, int completedSteps, long elapsedMs,
                              int modelCalls, boolean memorySaved, List<String> toolNames,
                              List<TraceRecorder.TraceEvent> validationTrace,
                              DiagnosisReport report, AgentRunResult runResult) { }

    /** 环境版本信息和本次所有脚本/真实模型案例。
     * @param suite 评测套件名
     * @param generatedAt UTC 报告生成时间
     * @param schema 本次隔离的数据库 schema
     * @param postgresVersion PostgreSQL 服务端版本
     * @param pgvectorVersion vector 扩展版本
     * @param chatModel Ollama 聊天模型名称
     * @param embeddingModel BGE embedding 模型名称
     * @param cases 本次所有已执行案例
     */
    private record EvaluationReport(String suite, String generatedAt, String schema,
                                    String postgresVersion, String pgvectorVersion,
                                    String chatModel, String embeddingModel, List<CaseReport> cases) { }
}
