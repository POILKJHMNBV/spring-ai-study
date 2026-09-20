package org.example.ai.eval;

import org.example.ai.harness.AgentRunResult;
import org.example.ai.harness.AgentRunner;
import org.example.ai.tool.KafkaToolMode;
import org.example.ai.tool.mock.MockScenario;
import org.example.ai.tool.mock.OpsMockDataProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day7 E01 LOCAL / MCP 对照测试。
 *
 * <p>
 * 运行前必须先启动 mcp-kafka-server。
 * </p>
 */
@SpringBootTest(
        properties = {
                "spring.ai.ollama.chat.temperature=0.0",
                "spring.ai.ollama.chat.seed=42"
        }
)
@ActiveProfiles("mcp")
class Day7McpComparisonIT {

    @Autowired
    private AgentRunner agentRunner;

    @Autowired
    private OpsMockDataProvider mockDataProvider;

    @Autowired
    private SyncMcpToolCallbackProvider mcpToolCallbackProvider;

    private final AgentEvaluator evaluator = new AgentEvaluator();

    /**
     * 首先验证 MCP Client 确实发现了
     * getKafkaStatus，而不是测试误走 LOCAL Tool。
     */
    @Test
    void shouldDiscoverKafkaToolFromMcpServer() {

        ToolCallback[] callbacks =
                mcpToolCallbackProvider
                        .getToolCallbacks();

        boolean found =
                Arrays.stream(callbacks)
                        .anyMatch(callback ->
                                "getKafkaStatus".equals(
                                        callback
                                                .getToolDefinition()
                                                .name()
                                )
                        );

        assertTrue(
                found,
                "MCP Server 未发现 getKafkaStatus"
        );
    }

    /**
     * 同一个 E01 分别通过 LOCAL 和 MCP Kafka Tool 执行。
     *
     * <p>
     * 比较的是最终主要诊断是否保持一致，
     * 而不是要求模型逐字输出完全相同文本。
     * </p>
     */
    @Test
    void e01ShouldReachSameMainDiagnosisWithLocalAndMcp()
            throws Exception {

        AgentEvalCase e01 =
                AgentEvalCases.all()
                        .stream()
                        .filter(evalCase ->
                                "E01".equals(
                                        evalCase.id()
                                )
                        )
                        .findFirst()
                        .orElseThrow();

        mockDataProvider.useScenario(
                MockScenario
                        .E01_THREAD_POOL_SATURATED
        );

        /*
         * ======================
         * LOCAL
         * ======================
         */
        long localStart =
                System.nanoTime();

        AgentRunResult localResult =
                agentRunner.run(
                        e01.prompt(),
                        "day7-e01-local",
                        false,
                        KafkaToolMode.LOCAL
                );

        long localElapsed =
                elapsedMillis(localStart);

        AgentEvalCaseResult localEval =
                evaluator.evaluate(
                        e01,
                        localResult,
                        localElapsed
                );

        /*
         * ======================
         * MCP
         * ======================
         */
        long mcpStart =
                System.nanoTime();

        AgentRunResult mcpResult =
                agentRunner.run(
                        e01.prompt(),
                        "day7-e01-mcp",
                        false,
                        KafkaToolMode.MCP
                );

        long mcpElapsed =
                elapsedMillis(mcpStart);

        AgentEvalCaseResult mcpEval =
                evaluator.evaluate(
                        e01,
                        mcpResult,
                        mcpElapsed
                );

        /*
         * 两条链路都必须正常完成。
         */
        assertEquals(
                AgentRunResult.RunStatus.COMPLETED,
                localResult.status()
        );

        assertEquals(
                AgentRunResult.RunStatus.COMPLETED,
                mcpResult.status()
        );

        /*
         * Day7 的核心：
         *
         * transport 改变以后，
         * E01 主要判断不能发生回归。
         */
        assertTrue(
                localEval.mainJudgmentPass(),
                "LOCAL E01 主要判断失败"
        );

        assertTrue(
                mcpEval.mainJudgmentPass(),
                "MCP E01 主要判断失败"
        );

        /*
         * 必要 Tool 选择也必须继续正确。
         */
        assertTrue(
                localEval.toolSelectionPass(),
                "LOCAL Tool selection failed"
        );

        assertTrue(
                mcpEval.toolSelectionPass(),
                "MCP Tool selection failed"
        );

        agentRunner.clearMemory(
                "day7-e01-local"
        );

        agentRunner.clearMemory(
                "day7-e01-mcp"
        );

        mockDataProvider.reset();
    }

    private long elapsedMillis(long startNanos) {
        return (
                System.nanoTime()
                        - startNanos
        ) / 1_000_000;
    }
}