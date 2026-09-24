package org.example.ai.tool.mock;

import org.example.ai.tool.dto.ErrorLog;
import org.example.ai.tool.dto.DependencyStatus;
import org.example.ai.tool.dto.KafkaStatus;
import org.example.ai.tool.dto.ServiceStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 运维工具的固定 Mock 数据源：为不同评测场景提供可重复的 Tool 输入数据。
 *
 * <p>设计背景：当前项目本身就是学习项目，Tool 暂不连接真实生产系统。
 * Day6 通过切换固定场景，对 Agent 进行可重复回归测试。</p>
 *
 * <p>注意：该类不是生产环境的故障模拟框架。
 * 后续接入真实测试环境后应替换为真实只读数据源。</p>
 *
 * <p>使用方式：
 * <ul>
 *   <li>调用 {@link #useScenario(MockScenario)} 切换场景</li>
 *   <li>调用 {@link #reset()} 恢复默认的 BASELINE 场景</li>
 *   <li>各 getter 方法根据当前场景返回对应的 Mock 数据</li>
 * </ul>
 * </p>
 */
@Component
public class OpsMockDataProvider {

    /**
     * E06 场景的 Kafka 工具延迟：故意设置成大于 AgentRunner 的 3 秒 Tool Timeout，
     * 用于验证超时重试机制。
     */
    private static final Duration KAFKA_TIMEOUT_DELAY = Duration.ofSeconds(5);

    /**
     * 当前使用的固定 Mock 场景。
     * 使用 {@link AtomicReference} 保证线程安全，Eval 串行执行，进程内只维护一个当前场景。
     */
    private final AtomicReference<MockScenario> currentScenario = new AtomicReference<>(MockScenario.BASELINE);

    /**
     * 切换当前 Mock 场景。
     *
     * @param scenario 固定评测场景，不能为 null
     * @throws NullPointerException 场景为 null
     */
    public void useScenario(MockScenario scenario) {
        currentScenario.set(
                Objects.requireNonNull(
                        scenario,
                        "scenario must not be null"
                )
        );
    }

    /**
     * 恢复 Day1~Day5 默认 Mock 数据（BASELINE 场景）。
     */
    public void reset() {
        currentScenario.set(MockScenario.BASELINE);
    }

    /**
     * 返回服务运行指标：CPU、内存、线程池等。
     *
     * @param serviceName 服务名称
     * @return 服务运行状态，根据当前场景返回不同数据
     */
    public ServiceStatus getServiceStatus(String serviceName) {

        MockScenario scenario = currentScenario.get();

        return switch (scenario) {

            case BASELINE -> new ServiceStatus(
                    serviceName,
                    96.0,
                    78.5,
                    200,
                    200,
                    true
            );

            case E01_THREAD_POOL_SATURATED -> new ServiceStatus(
                    serviceName,
                    55.0,
                    68.0,
                    200,
                    200,
                    true
            );

            case E02_CONSUMER_INSUFFICIENT -> new ServiceStatus(
                    serviceName,
                    35.0,
                    55.0,
                    60,
                    200,
                    true
            );

            case E03_DATABASE_SLOW -> new ServiceStatus(
                    serviceName,
                    45.0,
                    62.0,
                    125,
                    200,
                    true
            );

            case E04_RPC_TIMEOUT -> new ServiceStatus(
                    serviceName,
                    48.0,
                    60.0,
                    135,
                    200,
                    true
            );

            case E05_NO_ANOMALY -> new ServiceStatus(
                    serviceName,
                    30.0,
                    48.0,
                    40,
                    200,
                    true
            );

            case E06_KAFKA_TOOL_TIMEOUT -> new ServiceStatus(
                    serviceName,
                    40.0,
                    52.0,
                    65,
                    200,
                    true
            );

            case E07_UNKNOWN_INCIDENT -> new ServiceStatus(
                    serviceName,
                    42.0,
                    54.0,
                    70,
                    200,
                    true
            );
        };
    }

    /**
     * 返回 Kafka 消费状态：Lag、生产/消费速率、消费者数量。
     *
     * <p>E06 场景会主动休眠 5 秒，用于验证 {@link org.example.ai.harness.GuardedToolCallback} 的 3 秒超时和重试机制。</p>
     *
     * @param topic Kafka Topic 名称
     * @return Kafka 消费状态，根据当前场景返回不同数据
     */
    public KafkaStatus getKafkaStatus(String topic) {

        MockScenario scenario = currentScenario.get();

        if (scenario == MockScenario.E06_KAFKA_TOOL_TIMEOUT) {
            sleep();
        }

        return switch (scenario) {

            case BASELINE, E01_THREAD_POOL_SATURATED -> new KafkaStatus(
                    topic,
                    125_000,
                    5_000,
                    2_800,
                    3
            );

            case E02_CONSUMER_INSUFFICIENT -> new KafkaStatus(
                    topic,
                    120_000,
                    5_000,
                    1_800,
                    1
            );

            case E03_DATABASE_SLOW -> new KafkaStatus(
                    topic,
                    85_000,
                    4_000,
                    2_100,
                    3
            );

            case E04_RPC_TIMEOUT -> new KafkaStatus(
                    topic,
                    90_000,
                    4_000,
                    2_200,
                    3
            );

            case E05_NO_ANOMALY -> new KafkaStatus(
                    topic,
                    200,
                    3_000,
                    3_050,
                    3
            );

            case E06_KAFKA_TOOL_TIMEOUT -> new KafkaStatus(
                    topic,
                    50_000,
                    3_500,
                    2_000,
                    3
            );

            case E07_UNKNOWN_INCIDENT -> new KafkaStatus(
                    topic,
                    300,
                    3_000,
                    2_950,
                    3
            );
        };
    }

    /**
     * 返回错误日志摘要。
     *
     * <p>日志作为辅助线索，Day10 使用独立依赖指标交叉验证。
     * 不同场景返回不同的日志内容，模拟真实故障现场。</p>
     *
     * @param serviceName 服务名称
     * @param minutes     向前查询分钟数
     * @return 错误日志列表，根据当前场景返回不同数据
     */
    public List<ErrorLog> queryErrorLogs(String serviceName, int minutes) {

        MockScenario scenario = currentScenario.get();

        return switch (scenario) {

            case BASELINE -> List.of(
                    new ErrorLog(
                            "ERROR",
                            "TaskRejectedException: executor queue is full"
                    ),
                    new ErrorLog(
                            "WARN",
                            "downstream timeout rate=18%"
                    )
            );

            case E01_THREAD_POOL_SATURATED -> List.of(
                    new ErrorLog(
                            "ERROR",
                            "TaskRejectedException: executor queue is full"
                    )
            );

            case E02_CONSUMER_INSUFFICIENT ->
                    List.of();

            case E03_DATABASE_SLOW -> List.of(
                    new ErrorLog(
                            "WARN",
                            "db query p99=2500ms"
                    ),
                    new ErrorLog(
                            "WARN",
                            "hikari connection pool active=49 max=50"
                    )
            );

            case E04_RPC_TIMEOUT -> List.of(
                    new ErrorLog(
                            "ERROR",
                            "downstream RPC timeout rate=35%"
                    ),
                    new ErrorLog(
                            "WARN",
                            "payment-gateway rpc p99=3200ms"
                    )
            );

            case E05_NO_ANOMALY,
                    E06_KAFKA_TOOL_TIMEOUT ->
                    List.of();

            case E07_UNKNOWN_INCIDENT -> List.of(
                    new ErrorLog(
                            "ERROR",
                            "ZXQ917FrameCorruptionException: "
                                    + "frobnicator glyph checksum mismatch"
                    )
            );
        };
    }

    /**
     * 返回依赖指标：数据库 P99、连接池、下游 RPC P99、超时率。
     *
     * <p>E03 场景返回数据库慢查询指标，E04 场景返回 RPC 超时指标，其他场景返回正常指标。
     * 用于 Day10 交叉验证，确保 Agent 不会仅凭日志就断言根因。</p>
     *
     * @param serviceName 服务名称
     * @return 依赖指标，根据当前场景返回不同数据
     */
    public DependencyStatus getDependencyStatus(String serviceName) {
        MockScenario scenario = currentScenario.get();
        return new DependencyStatus(serviceName,
                scenario == MockScenario.E03_DATABASE_SLOW
                        ? new DependencyStatus.Database(2500.0, 49, 50)
                        : new DependencyStatus.Database(30.0, 10, 50),
                scenario == MockScenario.E04_RPC_TIMEOUT
                        ? new DependencyStatus.Rpc("payment-gateway", 3200.0, 0.35)
                        : new DependencyStatus.Rpc("payment-gateway", 80.0, 0.001));
    }

    /**
     * 模拟慢工具：休眠 5 秒，用于 E06 场景测试超时重试机制。
     *
     * <p>当 {@link org.example.ai.harness.GuardedToolCallback} 调用 {@code Future.cancel(true)} 后，
     * 正确恢复当前线程的中断状态并抛出异常。</p>
     *
     * @throws IllegalStateException 线程被中断
     */
    private void sleep() {
        try {
            Thread.sleep(OpsMockDataProvider.KAFKA_TIMEOUT_DELAY.toMillis());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "Mock Kafka query interrupted",
                    e
            );
        }
    }
}
