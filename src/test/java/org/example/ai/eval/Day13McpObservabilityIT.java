package org.example.ai.eval;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.ObservationView;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.example.ai.SpringAiStudyApplication;
import org.example.ai.harness.GuardedToolCallback;
import org.example.ai.harness.TraceRecorder;
import org.example.ai.observability.AgentTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
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
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Day13 真实 MCP 传输测试：需预先启动本仓库 mcp-kafka-server 的 8091 端口。
 *
 * <p>通过 Spring AI MCP Client 实际发现并调用远端 Tool。测试没有模型决策，
 * 与真实 Ollama Agent Eval 分开；PGVector 使用随机 schema 防止改写共享索引。</p>
 */
class Day13McpObservabilityIT {
    private static final Path REPORT = Path.of("target", "eval-results", "day13-mcp-observation.json");

    /**
     * 真正跨进程调用 Kafka MCP Server，检查返回、计时及 MCP→Tool→请求根的父子关系。
     * 工具输入中没有凭据，报告仅保存阶段名、父节点和指标计数。
     */
    @Test
    void realMcpTransportProducesNestedToolAndMcpObservations() throws Exception {
        String schema = "day13_mcp_it_" + UUID.randomUUID().toString().replace("-", "");
        Path manifest = Path.of("target", "day13", schema + ".json");
        JdbcTemplate jdbc = jdbc();
        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SpringAiStudyApplication.class)
                    .web(WebApplicationType.NONE)
                    .run("--spring.profiles.active=mcp", "--app.ops.data-source=MOCK",
                            "--management.tracing.sampling.probability=1.0",
                            "--spring.ai.vectorstore.pgvector.schema-name=" + schema,
                            "--rag.index.manifest-path=" + manifest)) {
                var provider = context.getBean(SyncMcpToolCallbackProvider.class);
                var telemetry = context.getBean(AgentTelemetry.class);
                var observations = context.getBean(ObservationRegistry.class);
                var meters = context.getBean(MeterRegistry.class);
                var tracer = context.getBean(Tracer.class);
                ToolCallback callback = Arrays.stream(provider.getToolCallbacks())
                        .filter(tool -> "getKafkaStatus".equals(tool.getToolDefinition().name()))
                        .findFirst().orElseThrow();
                List<ObservationEvent> captured = new CopyOnWriteArrayList<>();
                AtomicReference<String> rootTraceId = new AtomicReference<>();
                AtomicReference<String> rootSpanId = new AtomicReference<>();
                AtomicReference<String> workerTraceId = new AtomicReference<>();
                AtomicReference<String> workerSpanId = new AtomicReference<>();
                ToolCallback tracedCallback = new ToolCallback() {
                    /** 保持真实 MCP Tool Schema，确保包装器仍将其识别为远端 Kafka 工具。 */
                    @Override public ToolDefinition getToolDefinition() { return callback.getToolDefinition(); }
                    /** 保持真实 MCP Tool Metadata。 */
                    @Override public ToolMetadata getToolMetadata() { return callback.getToolMetadata(); }
                    /** 在真正执行远端回调的工作线程读取活动 Span，再委托真实传输。 */
                    @Override public String call(String input) {
                        Span active = tracer.currentSpan();
                        if (active != null) {
                            workerTraceId.set(active.context().traceId());
                            workerSpanId.set(active.context().spanId());
                        }
                        return callback.call(input);
                    }
                };
                observations.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
                    /** 捕获所有标准 Observation；筛选留待断言阶段。 */
                    @Override public boolean supportsContext(Observation.Context observation) { return true; }
                    /** 只采集阶段名称及父阶段名称。 */
                    @Override public void onStop(Observation.Context observation) {
                        ObservationView parent = observation.getParentObservation();
                        captured.add(new ObservationEvent(observation.getName(),
                                parent == null ? null : parent.getContextView().getName()));
                    }
                });
                ExecutorService executor = Executors.newSingleThreadExecutor();
                try {
                    GuardedToolCallback guarded = new GuardedToolCallback(tracedCallback, executor,
                            Duration.ofSeconds(3), 0, new TraceRecorder(), () -> 1, telemetry, true);
                    String result = Observation.createNotStarted("day13.mcp.it.root", observations)
                            .observe(() -> {
                                Span active = tracer.currentSpan();
                                assertNotNull(active, "请求根 Observation 应创建真实 Tracer Span");
                                rootTraceId.set(active.context().traceId());
                                rootSpanId.set(active.context().spanId());
                                return guarded.call("{\"topic\":\"order-topic\"}");
                            });
                    assertTrue(result.contains("order-topic"), "实际 MCP 响应应包含请求 Topic");
                    assertEquals(1, count(meters, "mcp.calls"));
                    assertEquals(1, meters.find("mcp.latency").timers().stream()
                            .mapToLong(t -> t.count()).sum());
                    assertTrue(captured.stream().anyMatch(e -> e.name().equals("tool.call")
                            && "day13.mcp.it.root".equals(e.parent())), "Tool 应位于请求根下");
                    assertTrue(captured.stream().anyMatch(e -> e.name().equals("tool.attempt")
                            && "tool.call".equals(e.parent())), "异步尝试应位于 Tool 节点下");
                    assertTrue(captured.stream().anyMatch(e -> e.name().equals("mcp.call")
                            && "tool.attempt".equals(e.parent())), "真实 MCP 应位于异步尝试子节点下");
                    assertNotNull(workerTraceId.get(), "远端 MCP 回调必须处在活动 Span 中");
                    assertEquals(rootTraceId.get(), workerTraceId.get(),
                            "跨 Executor 的实际 MCP 调用应保留请求 traceId");
                    assertNotEquals(rootSpanId.get(), workerSpanId.get(),
                            "MCP 子 Span 应有独立 spanId");
                    writeReport(captured, count(meters, "mcp.calls"), rootTraceId.get(), workerSpanId.get());
                } finally {
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
                }
            }
        } finally {
            // 仅删除本次测试创建的随机 schema。
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    /** MCP 测试报告用于复核跨进程调用，明确此测试没有真实模型决策。 */
    private void writeReport(List<ObservationEvent> observations, double calls,
                             String traceId, String mcpSpanId) throws Exception {
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, JsonMapper.builder().build().writerWithDefaultPrettyPrinter()
                .writeValueAsString(new McpReport("real-mcp-transport-without-llm", calls,
                        traceId, mcpSpanId, observations)),
                StandardCharsets.UTF_8);
    }

    /** 聚合同名但带不同安全状态标签的 Counter。 */
    private double count(MeterRegistry meters, String name) {
        return meters.find(name).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    /** 读取已配置 VM 数据库连接，避免把凭据写入测试或报告。 */
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

    /** 安全的 Observation 树边，父为空时表示独立根节点。 */
    private record ObservationEvent(String name, String parent) { }
    /** 明示真实 MCP 传输和无模型决策边界的测试报告。 */
    private record McpReport(String suite, double mcpCalls, String traceId, String mcpSpanId,
                             List<ObservationEvent> observationTree) { }
}
