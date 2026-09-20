package org.example.kafkamcp;

/**
 * Kafka 状态。
 *
 * <p>
 * 字段语义与主工程 Day2~Day6 的 KafkaStatus 保持一致。
 * MCP 只改变 transport，不改变业务 contract。
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