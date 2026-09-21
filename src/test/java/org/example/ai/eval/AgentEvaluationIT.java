package org.example.ai.eval;

import org.example.ai.harness.AgentRunResult;
import org.example.ai.harness.AgentRunner;
import org.example.ai.tool.mock.OpsMockDataProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day6 Agent 回归评测。
 *
 * <p>
 * 类名使用 IT 而不是 Test：
 * 普通 mvn test 不会默认执行这组需要真实 Ollama 的慢测试；
 * 需要评测时通过 -Dtest=AgentEvaluationIT 显式运行。
 * </p>
 */
@SpringBootTest(
        properties = {
                /*
                 * Eval 固定模型随机性。
                 *
                 * Spring AI 2.0.1 的 Ollama 配置直接位于
                 * spring.ai.ollama.chat.* 下。
                 */
                "spring.ai.ollama.chat.temperature=0.0",
                "spring.ai.ollama.chat.seed=42",
                /*
                 * Day8 正常运行使用 HTTP，
                 * 但历史 Eval 必须继续使用固定数据集，
                 * 否则每次网络状态都会污染基准结果。
                 */
                "app.ops.data-source=MOCK"
        }
)
class AgentEvaluationIT {

    private static final Path REPORT_PATH =
            Path.of(
                    "target",
                    "eval-results",
                    "day6-eval.json"
            );

    @Autowired
    private AgentRunner agentRunner;

    @Autowired
    private OpsMockDataProvider mockDataProvider;

    @Autowired
    private ChatModel chatModel;

    private final AgentEvaluator evaluator = new AgentEvaluator();

    /**
     * 一次运行 Day6 全部十个固定案例。
     *
     * <p>
     * 无论最终门槛是否通过，都先生成 JSON 报告，
     * 方便定位具体失败案例。
     * </p>
     */
    @Test
    void runDay6RegressionSuite() throws Exception {

        List<AgentEvalCaseResult> results =
                new ArrayList<>();

        try {
            for (AgentEvalCase evalCase :
                    AgentEvalCases.all()) {

                results.add(
                        runCase(evalCase)
                );
            }
        }
        finally {
            mockDataProvider.reset();
        }

        AgentEvalReport report =
                AgentEvalReport.of(
                        resolveModelName(),
                        results
                );

        writeReport(report);

        /*
         * Day6 第一阶段门槛：
         * 10 个案例至少 8 个主要判断正确。
         */
        assertTrue(
                report.mainJudgmentPassCount() >= 8,
                () -> "主要判断通过 "
                        + report.mainJudgmentPassCount()
                        + "/"
                        + report.totalCases()
                        + "，低于 Day6 的 8/10 门槛。"
                        + "详见 " + REPORT_PATH
        );

        /*
         * 所有案例必须受 MAX_STEPS=6 约束。
         */
        assertTrue(
                results.stream()
                        .allMatch(
                                AgentEvalCaseResult::stepPass
                        ),
                () -> "存在超过 6 步的案例，详见 "
                        + REPORT_PATH
        );

        /*
         * 实时指标不允许出现无法从 Tool Trace
         * 追溯的直接数值。
         */
        assertTrue(
                results.stream()
                        .allMatch(
                                AgentEvalCaseResult
                                        ::evidenceFaithfulnessPass
                        ),
                () -> "存在无法追溯的实时指标，详见 "
                        + REPORT_PATH
        );

        /*
         * 引用的 Markdown source 必须真实存在于
         * 当前请求的 RAG Trace。
         */
        assertTrue(
                results.stream()
                        .allMatch(
                                AgentEvalCaseResult
                                        ::sourceCitationPass
                        ),
                () -> "存在虚构知识来源，详见 "
                        + REPORT_PATH
        );

        AgentEvalCaseResult e06 =
                findResult(results, "E06");

        assertTrue(
                e06.exceptionHandlingPass(),
                () -> "E06 Kafka Tool 超时降级失败，详见 "
                        + REPORT_PATH
        );

        AgentEvalCaseResult e08 =
                findResult(results, "E08");

        /*
         * 越权请求必须 100% 被 Policy 拒绝。
         */
        assertTrue(
                e08.status()
                        == AgentRunResult.RunStatus.POLICY_REJECTED,
                () -> "E08 未被 ExecutionPolicy 拒绝，详见 "
                        + REPORT_PATH
        );

        AgentEvalCaseResult e10 =
                findResult(results, "E10");

        assertTrue(
                e10.mainJudgmentPass()
                        && e10.toolSelectionPass(),
                () -> "E10 conversationId 隔离失败，详见 "
                        + REPORT_PATH
        );
    }

    /**
     * 执行单个案例。
     */
    private AgentEvalCaseResult runCase(
            AgentEvalCase evalCase) {

        String conversationId =
                "eval-" + evalCase.id().toLowerCase();

        String setupConversationId =
                conversationId + "-source";

        /*
         * 每个案例开始前清空 Memory，
         * 避免 Eval 自己互相污染。
         */
        agentRunner.clearMemory(conversationId);
        agentRunner.clearMemory(setupConversationId);

        mockDataProvider.useScenario(
                evalCase.scenario()
        );

        /*
         * E09：
         * setup 与正式问题使用同一 conversationId，
         * 验证 Memory。
         *
         * E10：
         * setup 写入另一个 conversationId，
         * 正式问题使用空白会话，验证隔离。
         */
        if (evalCase.setupPrompt() != null) {

            String targetSetupConversation =
                    evalCase.setupSameConversation()
                            ? conversationId
                            : setupConversationId;

            agentRunner.run(
                    evalCase.setupPrompt(),
                    targetSetupConversation,
                    true
            );
        }

        long start = System.nanoTime();

        AgentRunResult result =
                agentRunner.run(
                        evalCase.prompt(),
                        conversationId,
                        true
                );

        long elapsedMs =
                (System.nanoTime() - start)
                        / 1_000_000;

        AgentEvalCaseResult evalResult =
                evaluator.evaluate(
                        evalCase,
                        result,
                        elapsedMs
                );

        /*
         * 当前 Case 完成后清理 Memory，
         * 下一案例从完全干净状态开始。
         */
        agentRunner.clearMemory(conversationId);
        agentRunner.clearMemory(setupConversationId);

        return evalResult;
    }

    private AgentEvalCaseResult findResult(
            List<AgentEvalCaseResult> results,
            String id) {

        return results.stream()
                .filter(result ->
                        id.equals(result.id()))
                .findFirst()
                .orElseThrow();
    }

    /**
     * 将每次评测结果持久化为结构化 JSON。
     */
    private void writeReport(
            AgentEvalReport report) throws Exception {

        Files.createDirectories(
                REPORT_PATH.getParent()
        );

        ObjectMapper objectMapper =
                new ObjectMapper();

        String json =
                objectMapper
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(report);

        Files.writeString(
                REPORT_PATH,
                json,
                StandardCharsets.UTF_8
        );
    }

    /**
     * 把本轮实际使用的模型写入 Eval Report，
     * 后续切模型后可以直接比较两份结果。
     */
    private String resolveModelName() {

        if (chatModel.getOptions()
                instanceof OllamaChatOptions options) {

            return Objects.requireNonNullElse(
                    options.getModel(),
                    "unknown-ollama-model"
            );
        }

        return chatModel
                .getClass()
                .getSimpleName();
    }
}

/**
 * 整个 Day6 Eval Suite 的结构化结果。
 */
record AgentEvalReport(
        String suite,
        String generatedAt,
        String model,
        int totalCases,
        int mainJudgmentPassCount,
        int overallPassCount,
        List<AgentEvalCaseResult> cases
) {

    static AgentEvalReport of(
            String model,
            List<AgentEvalCaseResult> cases) {

        List<AgentEvalCaseResult> immutableCases =
                List.copyOf(cases);

        int mainPassed = (int) immutableCases
                .stream()
                .filter(
                        AgentEvalCaseResult
                                ::mainJudgmentPass
                )
                .count();

        int overallPassed = (int) immutableCases
                .stream()
                .filter(
                        AgentEvalCaseResult
                                ::overallPass
                )
                .count();

        return new AgentEvalReport(
                "day6-agent-regression",
                OffsetDateTime.now(ZoneOffset.UTC)
                        .toString(),
                model,
                immutableCases.size(),
                mainPassed,
                overallPassed,
                immutableCases
        );
    }
}