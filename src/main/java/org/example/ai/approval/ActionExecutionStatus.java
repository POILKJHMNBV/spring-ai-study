package org.example.ai.approval;

/**
 * 动作执行结果。
 *
 * <p>Day16 只存在 {@link #SIMULATED_EXECUTION}；这里没有连接真实 Kafka 或其他运行环境的实现。</p>
 */
public enum ActionExecutionStatus {
    /**
     * 只生成模拟执行回执，没有修改任何外部系统。
     */
    SIMULATED_EXECUTION
}
