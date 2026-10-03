package org.example.ai.day20;

import org.example.ai.harness.AgentRunResult;
import org.example.ai.harness.AgentRunner;

import java.util.Objects;

/**
 * Day20 手写循环适配器，完全委托既有 AgentRunner，供同一数据集进行 A/B 实验。
 */
public final class ManualAgentRunner {
    /**
     * 原有手写执行器，保留其全部循环、策略与输出契约。
     */
    private final AgentRunner delegate;

    /**
     * 创建手写实现的实验入口。
     *
     * @param delegate 原有 AgentRunner 实例
     */
    public ManualAgentRunner(AgentRunner delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    /**
     * 执行原有完整循环。
     *
     * @param prompt         用户问题
     * @param conversationId 会话标识
     * @param memoryEnabled  是否启用成功对话记忆
     * @return 原有执行器返回的状态、报告与轨迹
     */
    public AgentRunResult run(String prompt, String conversationId, boolean memoryEnabled) {
        return delegate.run(prompt, conversationId, memoryEnabled);
    }

    /**
     * 清理实验会话。
     *
     * @param conversationId 待清理会话标识
     */
    public void clearMemory(String conversationId) {
        delegate.clearMemory(conversationId);
    }
}
