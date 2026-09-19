package org.example.ai.tool.mock;

/**
 * 固定 Mock 故障场景。
 *
 * <p>
 * Day6 Eval 通过切换场景获得可重复的 Tool 输入数据。
 * BASELINE 保留 Day1~Day5 原来的 Mock 行为，避免 Eval 改造影响前面学习内容。
 * </p>
 */
public enum MockScenario {
    /**
     * Day1~Day5 原有 Mock 数据。
     */
    BASELINE,

    /**
     * E01：业务线程池饱和。
     */
    E01_THREAD_POOL_SATURATED,

    /**
     * E02：Kafka Consumer 数量不足。
     */
    E02_CONSUMER_INSUFFICIENT,

    /**
     * E03：数据库响应慢、连接池接近耗尽。
     */
    E03_DATABASE_SLOW,

    /**
     * E04：下游 RPC 大量超时。
     */
    E04_RPC_TIMEOUT,

    /**
     * E05：当前系统没有明显异常。
     */
    E05_NO_ANOMALY,

    /**
     * E06：Kafka Tool 人工制造超时。
     */
    E06_KAFKA_TOOL_TIMEOUT,

    /**
     * E07：知识库中不存在的新型错误。
     */
    E07_UNKNOWN_INCIDENT
}
