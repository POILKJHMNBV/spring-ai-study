package org.example.kafkamcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Kafka MCP Tool。
 *
 * <p>
 * Day7 只向 MCP Server 暴露一个能力：
 * getKafkaStatus。
 *
 * 不暴露 Service Tool，也不暴露 Log Tool。
 * </p>
 */
@Slf4j
@Component
public class KafkaMcpTools {

    /**
     * 查询 Kafka Topic 状态。
     *
     * <p>
     * Tool 名、参数名和业务语义与原本本地
     * OpsTools#getKafkaStatus 完全一致。
     * </p>
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
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false
            )
    )
    public KafkaStatus getKafkaStatus(
            @McpToolParam(
                    description =
                            "需要查询的 Kafka Topic，例如 order-topic",
                    required = true
            )
            String topic) {

        /*
         * Agent 一侧已有 ExecutionPolicy。
         *
         * MCP Server 仍然再次校验，
         * 避免未来其他 MCP Client 绕过 Agent Policy
         * 直接访问任意 Topic。
         */
        if (!"order-topic".equals(topic)) {
            throw new IllegalArgumentException(
                    "Topic not allowed: " + topic
            );
        }

        log.info("McpTool called: getKafkaStatus(topic={})", topic);

        /*
         * 与当前仓库 BASELINE / E01 Kafka Mock
         * 保持完全相同：
         *
         * lag=125000
         * produceRate=5000
         * consumeRate=2800
         * consumerCount=3
         *
         * 因此 E01 LOCAL / MCP 对照只有 transport 变化。
         */
        return new KafkaStatus(
                topic,
                125_000,
                6_000,
                2_500,
                2
        );
    }
}