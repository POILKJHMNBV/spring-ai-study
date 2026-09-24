package org.example.ai.tool.mock;

/**
 * 固定 Mock 故障场景枚举：用于 Day6 评测的可重复回归测试。
 *
 * <p>设计背景：评测需要可重复的 Tool 输入数据，因此通过切换场景获得固定的 Mock 数据。
 * BASELINE 保留 Day1~Day5 原来的 Mock 行为，避免 Eval 改造影响前面学习内容。</p>
 *
 * <p>场景说明：
 * <ul>
 *   <li>{@link #BASELINE} — Day1~Day5 原有 Mock 数据，线程池饱和 + Kafka 积压</li>
 *   <li>{@link #E01_THREAD_POOL_SATURATED} — 业务线程池饱和，CPU 中等，线程池满</li>
 *   <li>{@link #E02_CONSUMER_INSUFFICIENT} — Kafka Consumer 数量不足，Lag 高，消费者少</li>
 *   <li>{@link #E03_DATABASE_SLOW} — 数据库响应慢，连接池接近耗尽</li>
 *   <li>{@link #E04_RPC_TIMEOUT} — 下游 RPC 大量超时</li>
 *   <li>{@link #E05_NO_ANOMALY} — 系统没有明显异常，测试 Agent 能否正确判断“无故障”</li>
 *   <li>{@link #E06_KAFKA_TOOL_TIMEOUT} — Kafka Tool 人工制造超时，测试超时重试机制</li>
 *   <li>{@link #E07_UNKNOWN_INCIDENT} — 知识库中不存在的新型错误，测试 Agent 应对未知故障的能力</li>
 * </ul>
 * </p>
 */
public enum MockScenario {
    /** Day1~Day5 原有 Mock 数据：线程池饱和 + Kafka 积压。 */
    BASELINE,
    /** E01：业务线程池饱和，CPU 中等，线程池满。 */
    E01_THREAD_POOL_SATURATED,
    /** E02：Kafka Consumer 数量不足，Lag 高，消费者少。 */
    E02_CONSUMER_INSUFFICIENT,
    /** E03：数据库响应慢，连接池接近耗尽。 */
    E03_DATABASE_SLOW,
    /** E04：下游 RPC 大量超时。 */
    E04_RPC_TIMEOUT,
    /** E05：系统没有明显异常，测试 Agent 能否正确判断“无故障”。 */
    E05_NO_ANOMALY,
    /** E06：Kafka Tool 人工制造超时，测试超时重试机制。 */
    E06_KAFKA_TOOL_TIMEOUT,
    /** E07：知识库中不存在的新型错误，测试 Agent 应对未知故障的能力。 */
    E07_UNKNOWN_INCIDENT
}
