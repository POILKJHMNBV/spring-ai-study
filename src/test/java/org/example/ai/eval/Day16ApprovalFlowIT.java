package org.example.ai.eval;

import org.example.ai.SpringAiStudyApplication;
import org.example.ai.approval.ActionExecutionResult;
import org.example.ai.approval.ActionExecutionStatus;
import org.example.ai.approval.ActionProposal;
import org.example.ai.approval.ApprovalAuditEvent;
import org.example.ai.approval.ApprovalService;
import org.example.ai.diagnosis.DiagnosisStatus;
import org.example.ai.diagnosis.EvidenceCatalog;
import org.example.ai.harness.AgentRunResult;
import org.example.ai.harness.AgentRunner;
import org.example.ai.harness.ExecutionPolicy;
import org.example.ai.rag.KnowledgeRetriever;
import org.example.ai.rag.RetrievedChunk;
import org.example.ai.tool.AgentToolProvider;
import org.example.ai.tool.KafkaToolMode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Day16 真实集成验收：使用本机 PostgreSQL/pgvector 检索知识，并以脚本模型完成提案审批链。 */
class Day16ApprovalFlowIT {
    /** 实验记录只写入本次测试的构建产物目录，不包含数据源凭据或模型上下文。 */
    private static final Path REPORT_PATH = Path.of("target", "eval-results", "day16-approval.json");
    /** Agent 只返回证据不足报告，避免把知识条目或提案回执伪装成当前环境事实。 */
    private static final String INSUFFICIENT_REPORT = """
            {"status":"INSUFFICIENT_EVIDENCE","summary":"仅创建了待审批提案，尚无新的环境执行证据",
             "facts":[],"hypotheses":[],"nextActions":[],
             "missingInformation":["需要当前 Consumer 实例状态和审批后的真实运行观测"]}
            """;

    /** 使用本地数据库隔离 schema 进行真实 pgvector 检索，验证模型只能提案且可信审批后只模拟执行。 */
    @Test
    void realPgvectorRetrievalAndScriptedAgentCompleteApprovalFlow() throws Exception {
        String schema = "day16_it_" + UUID.randomUUID().toString().replace("-", "");
        Path manifest = Path.of("target", "day16", schema + ".json");
        JdbcTemplate jdbc = jdbc();
        jdbc.execute("CREATE SCHEMA " + schema);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("testedAt", OffsetDateTime.now(ZoneOffset.ofHours(8)).toString());
        report.put("chatModel", "SCRIPTED_CHAT_MODEL");
        report.put("result", "FAILED");
        report.put("isolatedSchema", schema);
        try {
            report.put("postgresVersion", jdbc.queryForObject("SHOW server_version", String.class));
            String vectorVersion = jdbc.queryForObject(
                    "SELECT extversion FROM pg_extension WHERE extname='vector'", String.class);
            assertNotNull(vectorVersion, "虚拟机 PostgreSQL 必须提供 pgvector 扩展");
            report.put("pgvectorVersion", vectorVersion);

            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SpringAiStudyApplication.class)
                    .web(WebApplicationType.NONE)
                    .run("--spring.profiles.active=local", "--app.ops.data-source=MOCK",
                            "--spring.ai.vectorstore.pgvector.schema-name=" + schema,
                            "--rag.index.manifest-path=" + manifest)) {
                report.put("embeddingModel", context.getEnvironment()
                        .getRequiredProperty("spring.ai.ollama.embedding.model"));
                KnowledgeRetriever retriever = context.getBean(KnowledgeRetriever.class);
                String question = "order-topic Kafka 消费速率低于生产速率且 Consumer Lag 持续增长，应如何排查？";
                List<RetrievedChunk> retrieved = retriever.retrieve(question);
                assertFalse(retrieved.isEmpty(), "真实 PostgreSQL/pgvector 检索必须返回已索引知识条目");
                report.put("retrievedCount", retrieved.size());
                assertTrue(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class,
                                schema + ".vector_store"),
                        "向量记录必须位于本次随机隔离 schema");

                ApprovalService approvalService = context.getBean(ApprovalService.class);
                ChatFixture fixture = scriptedModelWithApprovalCallbacks();
                AgentRunner runner = new AgentRunner(ToolCallingManager.builder().build(),
                        context.getBean(AgentToolProvider.class), fixture.model(),
                        context.getBean(ExecutionPolicy.class), retriever,
                        context.getBean(ChatMemory.class));
                try {
                    AgentRunResult result = runner.run(question, "day16-" + schema, false,
                            KafkaToolMode.LOCAL);

                    assertEquals(AgentRunResult.RunStatus.COMPLETED, result.status(),
                            "确定性脚本模型应完成 Agent → proposal 流程");
                    assertEquals(DiagnosisStatus.INSUFFICIENT_EVIDENCE, result.report().status());
                    assertEquals(2, fixture.modelCalls().get(), "脚本先请求提案工具，再输出结构化最终报告");
                    assertTrue(fixture.prompts().get(0).getInstructions().stream()
                                    .map(Message::getText).filter(java.util.Objects::nonNull)
                                    .anyMatch(text -> retrieved.stream().anyMatch(chunk ->
                                            text.contains(chunk.source()) && text.contains(chunk.text().substring(
                                                    0, Math.min(32, chunk.text().length()))))),
                            "Agent 首轮提示必须含本机 pgvector 返回的真实知识块和来源");
                    assertModelRequestOnlyHasOperationalAndProposalTools(fixture.prompts().get(0));
                    report.put("agentStatus", result.status().name());
                    report.put("diagnosisStatus", result.report().status().name());
                    report.put("modelCalls", fixture.modelCalls().get());

                    List<ActionProposal> pending = approvalService.findPendingProposals();
                    assertEquals(1, pending.size(), "模型工具调用只创建一个待审批提案");
                    ActionProposal proposal = pending.get(0);
                    assertEquals("order-topic", proposal.target());
                    assertEquals(6, proposal.replicas());
                    assertTrue(proposal.approvalRequired());
                    assertEquals(List.of(ApprovalAuditEvent.PROPOSED), approvalService.auditRecords().stream()
                            .map(record -> record.event()).toList(),
                            "模型提案后应只有提案审计，不能产生批准或执行事件");
                    assertFalse(EvidenceCatalog.fromTrace(result.trace(), retrieved)
                                    .hasToolSource("proposeScaleConsumer"),
                            "提案回执不得进入本轮实时指标证据目录");

                    approvalService.approve(proposal.id(), "integration-operator");
                    ActionExecutionResult execution = approvalService.executeApproved(
                            proposal.id(), "integration-executor");
                    assertEquals(ActionExecutionStatus.SIMULATED_EXECUTION, execution.status());
                    assertTrue(execution.message().contains("模拟"));
                    assertEquals(List.of(ApprovalAuditEvent.PROPOSED, ApprovalAuditEvent.APPROVED,
                                    ApprovalAuditEvent.EXECUTED),
                            approvalService.auditRecords().stream().map(record -> record.event()).toList());
                    report.put("proposalId", proposal.id());
                    report.put("approvalRequired", proposal.approvalRequired());
                    report.put("executionStatus", execution.status().name());
                    report.put("auditEvents", approvalService.auditRecords().stream()
                            .map(record -> record.event().name()).toList());
                    report.put("result", "PASSED");
                } finally {
                    runner.shutdownToolExecutor();
                }
            }
        } finally {
            // schema 是本测试随机生成的，清理范围仅包括本次隔离索引及对应同步清单。
            try {
                jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
                report.put("schemaCleaned", true);
            } finally {
                Files.deleteIfExists(manifest);
                Files.createDirectories(REPORT_PATH.getParent());
                Files.writeString(REPORT_PATH,
                        JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report),
                        StandardCharsets.UTF_8);
            }
        }
    }

    /** 创建一次本机 PostgreSQL 连接，连接属性沿用项目 application.yaml 中已完成的配置。
     * @return 可用于隔离 schema 创建、检查和清理的 JDBC 模板
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

    /** 创建一个确定性脚本模型：首轮请求真实注册的提案工具，次轮只输出证据不足报告。
     * @return 捕获所有模型请求选项和次数的脚本模型夹具
     */
    private ChatFixture scriptedModelWithApprovalCallbacks() {
        AtomicInteger modelCalls = new AtomicInteger();
        List<Prompt> prompts = new CopyOnWriteArrayList<>();
        ToolCallback[] seededApprovalCallbacks = ToolCallbacks.from(new SeededApprovalTools());
        OllamaChatOptions baseOptions = OllamaChatOptions.builder()
                .toolCallbacks(seededApprovalCallbacks)
                .build();
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(baseOptions);
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0, Prompt.class);
            prompts.add(prompt);
            return modelCalls.getAndIncrement() == 0
                    ? toolCallResponse("proposeScaleConsumer",
                            "{\"topic\":\"order-topic\",\"replicas\":6,\"reason\":\"当前知识提示应先由操作人员核实再扩容\"}")
                    : response(INSUFFICIENT_REPORT);
        });
        return new ChatFixture(model, modelCalls, prompts);
    }

    /** 检查一次 Agent 请求携带项目工具和提案工具，并丢弃模型默认 options 中预置的审批工具。
     * @param prompt AgentRunner 交给脚本 ChatModel 的首轮提示
     */
    private void assertModelRequestOnlyHasOperationalAndProposalTools(Prompt prompt) {
        OllamaChatOptions options = assertInstanceOf(OllamaChatOptions.class, prompt.getOptions());
        Set<String> names = options.getToolCallbacks().stream()
                .map(callback -> callback.getToolDefinition().name())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertTrue(names.contains("proposeScaleConsumer"), "Agent 必须能够提出受限审批申请");
        assertTrue(names.contains("getKafkaStatus"), "Agent 应继续保留已有只读运维工具");
        assertFalse(names.contains("approve"), "预置审批工具必须从当前请求选项移除");
        assertFalse(names.contains("reject"), "预置拒绝工具必须从当前请求选项移除");
        assertFalse(names.contains("executeApproved"), "预置执行入口必须从当前请求选项移除");
    }

    /** 创建 Spring AI AssistantMessage 中的确定性工具调用响应。
     * @param toolName 由脚本模型请求的工具名称
     * @param arguments 符合该工具 Schema 的 JSON 参数
     * @return 携带指定函数调用的聊天响应
     */
    private ChatResponse toolCallResponse(String toolName, String arguments) {
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                "day16-script-call", "function", toolName, arguments);
        AssistantMessage assistant = AssistantMessage.builder().content("").toolCalls(List.of(call)).build();
        return new ChatResponse(List.of(new Generation(assistant)));
    }

    /** 创建只包含一条 Assistant 正文的固定响应。
     * @param text 模拟模型生成的正文文本
     * @return 单条生成结果构成的聊天响应
     */
    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** 模拟错误配置：这些回调故意出现在基础模型选项中，AgentRunner 不得将其传给模型请求。 */
    private static final class SeededApprovalTools {
        /** 模拟未经授权的批准工具回调。
         * @param proposalId 任意提案标识
         * @return 固定标记字符串
         */
        @Tool(description = "测试专用的预置审批工具")
        public String approve(@ToolParam(description = "提案标识") String proposalId) {
            return "seeded-approval-" + proposalId;
        }

        /** 模拟未经授权的拒绝工具回调。
         * @param proposalId 任意提案标识
         * @return 固定标记字符串
         */
        @Tool(description = "测试专用的预置拒绝工具")
        public String reject(@ToolParam(description = "提案标识") String proposalId) {
            return "seeded-rejection-" + proposalId;
        }

        /** 模拟未经授权的执行工具回调。
         * @param proposalId 任意提案标识
         * @return 固定标记字符串
         */
        @Tool(description = "测试专用的预置执行工具")
        public String executeApproved(@ToolParam(description = "提案标识") String proposalId) {
            return "seeded-execution-" + proposalId;
        }
    }

    /** 保存脚本模型、调用次数和请求提示，供同一全链路 IT 做确定性断言。
     * @param model 确定性 ChatModel 桩
     * @param modelCalls 已发生的模型调用计数
     * @param prompts 按请求顺序捕获的模型提示
     */
    private record ChatFixture(ChatModel model, AtomicInteger modelCalls, List<Prompt> prompts) {
    }
}
