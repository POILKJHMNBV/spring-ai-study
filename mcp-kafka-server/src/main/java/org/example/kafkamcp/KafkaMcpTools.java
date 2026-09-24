package org.example.kafkamcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Kafka MCP 工具：通过 MCP 协议向 Agent 暴露 Kafka 状态查询能力。
 *
 * <p>设计背景：Day7 引入 MCP 协议后，getKafkaStatus 工具从本地 Java 方法迁移到独立 MCP Server。
 * 主工程通过 MCP Client 远程调用该工具，验证跨进程工具调用的可行性。</p>
 *
 * <p>设计要点：
 * <ul>
 *   <li>只暴露一个工具：getKafkaStatus，不暴露 Service Tool 和 Log Tool</li>
 *   <li>工具名、参数名和业务语义与主工程的 OpsTools#getKafkaStatus 完全一致</li>
 *   <li>MCP Server 仍然再次校验 Topic 白名单，避免未来其他 MCP Client 绕过 Agent Policy</li>
 *   <li>返回的 Mock 数据与主工程 BASELINE 场景保持一致，确保 LOCAL / MCP 对照只有 transport 变化</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class KafkaMcpTools {

    /**
     * 查询 Kafka Topic 状态：Consumer Lag、生产速率、消费速率、消费者数量。
     *
     * <p>工具名、参数名和业务语义与主工程 OpsTools#getKafkaStatus 完全一致。
     * 这样 Agent 不需要关心工具来自本地还是远程 MCP Server。</p>
     *
     * @param topic Kafka Topic 名称，例如 "order-topic"
     * @return Kafka 消费状态
     * @throws IllegalArgumentException Topic 不在白名单中
     */
    @McpTool(
            name = "getKafkaStatus",
            description = """
                    查询指定 Kafka Topic 当前的消费状态，
                    包括 Consumer Lag、生产速率、消费速率和消费者数量。
                    当需要判断 Kafka 是否存在消息积压、
                    消费速度不足或消费者数量不足时使用。
                    """,
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true,      // 只读工具，不修改状态
                    destructiveHint = false,  // 非破坏性操作
                    idempotentHint = true,    // 幂等操作，多次调用结果相同
                    openWorldHint = false     // 封闭世界，只查询已知数据
            )
    )
    public KafkaStatus getKafkaStatus(
            @McpToolParam(
                    description = "需要查询的 Kafka Topic，例如 order-topic",
                    required = true
            )
            String topic) {

        // MCP Server 再次校验 Topic 白名单，避免未来其他 MCP Client 绕过 Agent Policy 直接访问任意 Topic。
        if (!"order-topic".equals(topic)) {
            throw new IllegalArgumentException(
                    "Topic not allowed: " + topic
            );
        }

        log.info("McpTool called: getKafkaStatus(topic={})", topic);

        // 与主工程 BASELINE / E01 Kafka Mock 保持完全相同的数据，确保 LOCAL / MCP 对照只有 transport 变化。
        return new KafkaStatus(
                topic,
                125_000,    // lag
                6_000,      // produceRate
                2_500,      // consumeRate
                2           // consumerCount
        );
    }
}