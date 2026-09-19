package org.example.ai.tool;

import lombok.extern.slf4j.Slf4j;
import org.example.ai.tool.dto.ErrorLog;
import org.example.ai.tool.dto.KafkaStatus;
import org.example.ai.tool.dto.ServiceStatus;
import org.example.ai.tool.mock.OpsMockDataProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent 可调用的三个只读运维 Tool。
 *
 * <p>
 * Tool 定义保持稳定，具体 Mock 数据交给 OpsMockDataProvider。
 * 这样 Day6 可以切换场景，而不会污染 Tool Calling 本身。
 * </p>
 */
@Slf4j
@Component
public class OpsTools {
    private final OpsMockDataProvider dataProvider;
    public OpsTools(OpsMockDataProvider dataProvider) {
        this.dataProvider = dataProvider;
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

        return dataProvider.getServiceStatus(serviceName);
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

        return dataProvider.queryErrorLogs(serviceName, minutes);
    }
}
