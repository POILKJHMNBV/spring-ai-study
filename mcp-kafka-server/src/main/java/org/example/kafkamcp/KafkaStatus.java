package org.example.kafkamcp;

/**
 * Kafka Topic 消费状态快照：通过 MCP 协议传输的数据结构。
 *
 * <p>字段语义与主工程 Day2~Day6 的 KafkaStatus 保持一致。
 * MCP 只改变 transport，不改变业务 contract。</p>
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code topic} — Kafka Topic 名称，例如 "order-topic"</li>
 *   <li>{@code lag} — 消费 Lag，即未消费的消息数量</li>
 *   <li>{@code produceRate} — 生产速率，每秒新增消息数</li>
 *   <li>{@code consumeRate} — 消费速率，每秒处理消息数</li>
 *   <li>{@code consumerCount} — 活跃消费者数量</li>
 * </ul>
 * </p>
 *
 * @param topic         Kafka Topic
 * @param lag           Consumer Lag
 * @param produceRate   每秒生产消息数量
 * @param consumeRate   每秒消费消息数量
 * @param consumerCount Consumer 数量
 */
public record KafkaStatus(
        String topic,
        long lag,
        double produceRate,
        double consumeRate,
        int consumerCount
) {
}