package org.example.ai.tool.dto;

/**
 * Kafka Topic 消费状态快照：包含 Lag、生产/消费速率和消费者数量。
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
 * <p>判断逻辑：
 * <ul>
 *   <li>{@code lag > 0} 且 {@code produceRate > consumeRate}：消费速度不足，可能积压</li>
 *   <li>{@code consumerCount < partitionCount}：消费者数量不足，未充分利用分区</li>
 * </ul>
 * </p>
 */
public record KafkaStatus(
        String topic,
        long lag,
        long produceRate,
        long consumeRate,
        int consumerCount
) {
}
