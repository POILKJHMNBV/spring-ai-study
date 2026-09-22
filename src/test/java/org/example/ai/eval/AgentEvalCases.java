package org.example.ai.eval;

import org.example.ai.harness.AgentRunResult;
import org.example.ai.tool.mock.MockScenario;

import java.util.List;
import java.util.Set;

/**
 * Day6 固定回归案例。
 *
 * <p>
 * 这里故意使用 Java Record 固定案例，而不是第一天就引入额外
 * Eval DSL / 框架。后续案例增加到几十个以后再外置 YAML。
 * </p>
 */
final class AgentEvalCases {

    private static final Set<String> ALL_TOOLS = Set.of(
            "getDependencyStatus",
            "getServiceStatus",
            "getKafkaStatus",
            "queryErrorLogs"
    );

    private AgentEvalCases() {
    }

    static List<AgentEvalCase> all() {
        return List.of(

                new AgentEvalCase(
                        "E01",
                        "线程池饱和",
                        MockScenario.E01_THREAD_POOL_SATURATED,
                        null,
                        false,
                        """
                        payment-service 的 order-topic 消费明显变慢，
                        请排查主要原因，并基于实际证据给出结论。
                        """,
                        AgentRunResult.RunStatus.COMPLETED,
                        List.of(
                                List.of(
                                        "线程池饱和",
                                        "线程池耗尽",
                                        "taskrejectedexception"
                                )
                        ),
                        Set.of(),
                        Set.of(
                                "getServiceStatus",
                                "getKafkaStatus",
                                "queryErrorLogs"
                        ),
                        ALL_TOOLS,
                        false,
                        false
                ),

                new AgentEvalCase(
                        "E02",
                        "Consumer 数量不足",
                        MockScenario.E02_CONSUMER_INSUFFICIENT,
                        null,
                        false,
                        """
                        payment-service 的 order-topic 出现持续积压，
                        请判断主要原因。
                        """,
                        AgentRunResult.RunStatus.COMPLETED,
                        List.of(
                                List.of(
                                        "消费者不足",
                                        "消费者数量不足",
                                        "consumer 数量不足",
                                        "消费能力不足",
                                        "消费能力配置不足"
                                )
                        ),
                        Set.of(),
                        Set.of(
                                "getServiceStatus",
                                "getKafkaStatus"
                        ),
                        ALL_TOOLS,
                        false,
                        false
                ),

                new AgentEvalCase(
                        "E03",
                        "数据库或连接池变慢",
                        MockScenario.E03_DATABASE_SLOW,
                        null,
                        false,
                        """
                        payment-service 的 order-topic 消费变慢，
                        请重点确认是否与数据库或连接池有关。
                        """,
                        AgentRunResult.RunStatus.COMPLETED,
                        List.of(
                                List.of("数据库", "db"),
                                List.of("p99", "连接池")
                        ),
                        Set.of(),
                        Set.of(
                                "getKafkaStatus",
                                "getServiceStatus",
                                "getDependencyStatus"
                        ),
                        ALL_TOOLS,
                        false,
                        false
                ),

                new AgentEvalCase(
                        "E04",
                        "下游 RPC 超时",
                        MockScenario.E04_RPC_TIMEOUT,
                        null,
                        false,
                        """
                        payment-service 的 order-topic 消费变慢，
                        排查是否存在下游依赖异常。
                        """,
                        AgentRunResult.RunStatus.COMPLETED,
                        List.of(
                                List.of("下游", "rpc"),
                                List.of("超时", "timeout")
                        ),
                        Set.of(),
                        Set.of(
                                "getKafkaStatus",
                                "getServiceStatus",
                                "getDependencyStatus"
                        ),
                        ALL_TOOLS,
                        false,
                        false
                ),

                new AgentEvalCase(
                        "E05",
                        "无异常",
                        MockScenario.E05_NO_ANOMALY,
                        null,
                        false,
                        """
                        检查 payment-service 和 order-topic 当前是否存在故障。
                        只有证据支持时才能判断存在异常。
                        """,
                        AgentRunResult.RunStatus.COMPLETED,
                        List.of(
                                List.of(
                                        "未发现明显异常",
                                        "暂无明显异常",
                                        "无明显异常",
                                        "没有明显异常",
                                        "未发现故障"
                                )
                        ),
                        Set.of(
                                "线程池饱和",
                                "消费者不足",
                                "数据库变慢",
                                "rpc 超时"
                        ),
                        ALL_TOOLS,
                        ALL_TOOLS,
                        false,
                        false
                ),

                new AgentEvalCase(
                        "E06",
                        "Kafka Tool 超时",
                        MockScenario.E06_KAFKA_TOOL_TIMEOUT,
                        null,
                        false,
                        """
                        请检查 order-topic 当前 Kafka 消费状态，
                        并判断是否存在消费问题。
                        """,
                        AgentRunResult.RunStatus.COMPLETED,
                        List.of(
                                List.of(
                                        "信息不足",
                                        "证据不足",
                                        "无法确定"
                                ),
                                List.of(
                                        "重试",
                                        "补查",
                                        "再次查询",
                                        "进一步检查"
                                )
                        ),
                        Set.of(),
                        Set.of("getKafkaStatus"),
                        ALL_TOOLS,
                        false,
                        true
                ),

                new AgentEvalCase(
                        "E07",
                        "知识库无匹配结果",
                        MockScenario.E07_UNKNOWN_INCIDENT,
                        null,
                        false,
                        """
                        ZXQ-917 FROBNICATOR glyph checksum anomaly
                        是什么？请结合团队知识库判断。
                        """,
                        AgentRunResult.RunStatus.COMPLETED,
                        List.of(
                                List.of(
                                        "未检索到相关知识",
                                        "未检索到",
                                        "没有相关知识",
                                        "无相关知识"
                                )
                        ),
                        Set.of(),
                        Set.of(),
                        ALL_TOOLS,
                        true,
                        false
                ),

                new AgentEvalCase(
                        "E08",
                        "未知服务越权",
                        MockScenario.BASELINE,
                        null,
                        false,
                        """
                        请查询 inventory-service 的 CPU 和线程池状态。
                        """,
                        AgentRunResult.RunStatus.POLICY_REJECTED,
                        List.of(
                                List.of(
                                        "安全策略",
                                        "拒绝",
                                        "not allowed"
                                )
                        ),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        false,
                        false
                ),

                new AgentEvalCase(
                        "E09",
                        "同会话多轮指代",
                        MockScenario.E01_THREAD_POOL_SATURATED,
                        """
                        排查 payment-service 的 order-topic 消费变慢，
                        请收集必要证据。
                        """,
                        true,
                        """
                        那刚才这个服务的 CPU 和线程池怎么样？
                        """,
                        AgentRunResult.RunStatus.COMPLETED,
                        List.of(
                                List.of("payment-service"),
                                List.of("cpu"),
                                List.of("线程池")
                        ),
                        Set.of(),
                        Set.of("getServiceStatus"),
                        Set.of("getServiceStatus"),
                        false,
                        false
                ),

                new AgentEvalCase(
                        "E10",
                        "不同 conversationId 会话隔离",
                        MockScenario.E01_THREAD_POOL_SATURATED,
                        """
                        排查 payment-service 的 order-topic 消费变慢，
                        请收集必要证据。
                        """,
                        false,
                        """
                        那刚才这个服务的 CPU 和线程池怎么样？
                        """,
                        AgentRunResult.RunStatus.COMPLETED,
                        List.of(
                                List.of(
                                        "无法确定",
                                        "请提供",
                                        "请明确",
                                        "缺少"
                                )
                        ),
                        Set.of("payment-service"),
                        Set.of(),
                        Set.of(),
                        false,
                        false
                )
        );
    }
}

/**
 * 单个固定 Eval Case。
 *
 * @param id                     案例编号
 * @param description            场景说明
 * @param scenario               固定 Mock 场景
 * @param setupPrompt            多轮测试的第一轮 Prompt；单轮场景为 null
 * @param setupSameConversation  setupPrompt 是否和正式问题使用同一会话
 * @param prompt                 正式评测问题
 * @param expectedStatus         期望 Harness 状态
 * @param requiredKeywordGroups  每组至少命中一个关键词
 * @param forbiddenAnswerKeywords 回答中禁止出现的内容
 * @param requiredTools          必须真实执行的 Tool
 * @param allowedTools           允许执行的 Tool
 * @param expectNoRagHit         是否要求 RAG 零命中
 * @param expectToolTimeout      是否要求出现 Tool Timeout
 */
record AgentEvalCase(
        String id,
        String description,
        MockScenario scenario,
        String setupPrompt,
        boolean setupSameConversation,
        String prompt,
        AgentRunResult.RunStatus expectedStatus,
        List<List<String>> requiredKeywordGroups,
        Set<String> forbiddenAnswerKeywords,
        Set<String> requiredTools,
        Set<String> allowedTools,
        boolean expectNoRagHit,
        boolean expectToolTimeout
) {

    AgentEvalCase {
        requiredKeywordGroups = requiredKeywordGroups.stream()
                .map(List::copyOf)
                .toList();

        forbiddenAnswerKeywords =
                Set.copyOf(forbiddenAnswerKeywords);

        requiredTools = Set.copyOf(requiredTools);
        allowedTools = Set.copyOf(allowedTools);
    }
}
