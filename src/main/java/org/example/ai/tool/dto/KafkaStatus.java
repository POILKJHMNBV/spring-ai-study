package org.example.ai.tool.dto;

public record KafkaStatus(
        String topic,
        long lag,
        long produceRate,
        long consumeRate,
        int consumerCount
) {
}
