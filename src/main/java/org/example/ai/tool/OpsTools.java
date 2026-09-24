package org.example.ai.tool;

import lombok.extern.slf4j.Slf4j;
import org.example.ai.tool.client.LogQueryClient;
import org.example.ai.tool.client.DependencyMetricsClient;
import org.example.ai.tool.dto.DependencyStatus;
import org.example.ai.tool.client.ServiceMetricsClient;
import org.example.ai.tool.dto.ErrorLog;
import org.example.ai.tool.dto.KafkaStatus;
import org.example.ai.tool.dto.ServiceStatus;
import org.example.ai.tool.mock.OpsMockDataProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent 可调用的四个只读运维工具：服务指标、Kafka 状态、依赖指标、错误日志。
 *
 * <p>设计原则：
 * <ul>
 *   <li>工具定义保持稳定，具体数据来源交给底层 Client（HTTP / Mock）</li>
 *   <li>每个工具都有详细的 description，帮助 LLM 理解何时应该调用</li>
 *   <li>所有工具都是只读的，不会修改生产环境状态</li>
 * </ul>
 * </p>
 *
 * <p>工具说明：
 * <ul>
 *   <li>{@link #getServiceStatus} — 查询服务自身的 CPU、内存、线程池等指标</li>
 *   <li>{@link #getKafkaStatus} — 查询 Kafka Topic 的消费 Lag、生产/消费速率</li>
 *   <li>{@link #getDependencyStatus} — 查询数据库 P99、连接池、下游 RPC 指标</li>
 *   <li>{@link #queryErrorLogs} — 查询服务最近一段时间的错误日志摘要</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class OpsTools {
    /**
     * Kafka LOCAL 模式仍然使用现有 Mock。
     * MCP 模式下 {@link AgentToolProvider} 会把该工具替换为远程 MCP 实现。
     */
    private final OpsMockDataProvider dataProvider;
    /** 服务指标来源：HTTP Adapter 或 Mock。 */
    private final ServiceMetricsClient serviceMetricsClient;
    /** 日志查询来源：HTTP Adapter 或 Mock。 */
    private final LogQueryClient logQueryClient;
    /** DB/RPC 指标来源：HTTP Adapter 或 Mock，与工具 Schema 解耦。 */
    private final DependencyMetricsClient dependencyMetricsClient;
    public OpsTools(OpsMockDataProvider dataProvider, ServiceMetricsClient serviceMetricsClient, LogQueryClient logQueryClient,
                    DependencyMetricsClient dependencyMetricsClient) {
        this.dataProvider = dataProvider;
        this.serviceMetricsClient = serviceMetricsClient;
        this.logQueryClient = logQueryClient;
        this.dependencyMetricsClient = dependencyMetricsClient;
    }

    /** 获取独立依赖证据，日志和知识库不能替代当前指标。 */
    @Tool(description = "查询服务的数据库 P99(ms)、连接池占用及下游 RPC P99(ms)、超时率(0～1)。判断 DB 慢或 RPC 超时时必须与服务和 Kafka 指标交叉验证；失败表示证据不可用。")
    public DependencyStatus getDependencyStatus(
            @ToolParam(description = "需要查询的服务名称，例如 payment-service") String serviceName) {
        return dependencyMetricsClient.getDependencyStatus(serviceName);
    }

    @Tool(
            description = """
                    查询指定 Java 服务当前的运行状态，
                    包括 CPU、内存、业务线程池使用情况和实例健康状态。
                    当需要判断服务自身是否存在 CPU 过高、线程池饱和、
                    处理能力下降等问题时使用。
                    """
    )
    public ServiceStatus getServiceStatus(
            @ToolParam(description = "需要查询的服务名称，例如 payment-service")
            String serviceName) {

        log.info("Tool called: getServiceStatus(serviceName={})", serviceName);

        return serviceMetricsClient.getServiceStatus(serviceName);
    }

    @Tool(
            description = """
                    查询指定 Kafka Topic 当前的消费状态，
                    包括 Consumer Lag、生产速率、消费速率和消费者数量。
                    当需要判断 Kafka 是否存在消息积压、
                    消费速度不足或消费者数量不足时使用。
                    """
    )
    public KafkaStatus getKafkaStatus(
            @ToolParam(description = "需要查询的 Kafka Topic，例如 order-topic")
            String topic) {

        log.info("Tool called: getKafkaStatus(topic={})", topic);

        return dataProvider.getKafkaStatus(topic);
    }

    @Tool(
            description = """
                    查询指定 Java 服务最近一段时间的错误日志摘要。
                    当需要根据异常、超时、线程池拒绝等日志进一步定位故障原因时使用。
                    """
    )
    public List<ErrorLog> queryErrorLogs(
            @ToolParam(description = "需要查询日志的服务名称，例如 payment-service")
            String serviceName,

            @ToolParam(description = "向前查询多少分钟，例如 10")
            int minutes) {

        log.info(
                "Tool called: queryErrorLogs(serviceName={}, minutes={})",
                serviceName,
                minutes
        );

        return logQueryClient.queryErrorLogs(serviceName, minutes);
    }
}
