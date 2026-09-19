package org.example.ai.tool.mock;

import org.example.ai.tool.dto.ErrorLog;
import org.example.ai.tool.dto.KafkaStatus;
import org.example.ai.tool.dto.ServiceStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 运维 Tool 的固定 Mock 数据源。
 *
 * <p>
 * 当前项目本身就是学习项目，Tool 暂不连接真实生产系统。
 * Day6 通过切换固定场景，对 Agent 进行可重复回归测试。
 * </p>
 *
 * <p>
 * 注意：该类不是生产环境的故障模拟框架。
 * 后续接入真实测试环境后应替换为真实只读数据源。
 * </p>
 */
@Component
public class OpsMockDataProvider {

    /**
     * E06 故意设置成大于 AgentRunner 的 3 秒 Tool Timeout。
     */
    private static final Duration KAFKA_TIMEOUT_DELAY = Duration.ofSeconds(5);

    /**
     * 当前使用的固定 Mock 场景。
     *
     * <p>
     * Eval 串行执行，因此一个进程内只维护一个当前场景。
     * </p>
     */
    private final AtomicReference<MockScenario> currentScenario = new AtomicReference<>(MockScenario.BASELINE);

    /**
     * 切换当前 Mock 场景。
     *
     * @param scenario 固定评测场景
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
     * 恢复 Day1~Day5 默认 Mock 数据。
     */
    public void reset() {
        currentScenario.set(MockScenario.BASELINE);
    }

    /**
     * 返回服务运行指标。
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
     * 返回 Kafka 消费状态。
     *
     * <p>
     * E06 会主动休眠 5 秒，
     * 用于验证 GuardedToolCallback 的 3 秒超时和重试机制。
     * </p>
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
     * 返回错误日志。
     *
     * <p>
     * DB / RPC 暂时仍通过现有日志 Tool 提供证据，
     * Day6 不为了 Eval 增加新的数据库/RPC Tool，
     * 避免偏离第一周“三个只读 Tool”的范围。
     * </p>
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
     * 模拟慢 Tool。
     *
     * <p>
     * 当 GuardedToolCallback 调用 Future.cancel(true) 后，
     * 正确恢复当前线程的中断状态。
     * </p>
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