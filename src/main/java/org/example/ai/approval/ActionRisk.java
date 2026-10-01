package org.example.ai.approval;

/**
 * 由服务端根据动作白名单决定的风险等级。
 *
 * <p>此枚举不会作为 Agent 工具参数暴露，模型不能自行降低提案风险等级。</p>
 */
public enum ActionRisk {
    /**
     * Consumer 扩容会改变运行环境，因此 Day16 固定标记为中等风险。
     */
    MEDIUM
}
