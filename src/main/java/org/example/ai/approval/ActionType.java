package org.example.ai.approval;

/**
 * Day16 允许提出的动作种类。
 *
 * <p>枚举采用服务端固定白名单；模型只能从已注册的提案工具提出这些动作，不能提交任意动作名。
 * 后续增加动作前，必须先为该动作补齐独立参数校验、审批规则和执行器行为。</p>
 */
public enum ActionType {
    /**
     * 增加指定 Kafka Topic 的 Consumer 副本数量。
     */
    SCALE_CONSUMER
}
