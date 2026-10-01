package org.example.ai.approval;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

/**
 * Day16 唯一动作执行器：构造明确的模拟回执，不连接 Kafka 或任何外部系统。
 */
@Component
public class SimulatedActionExecutor implements ActionExecutor {
    /**
     * 返回一条说明动作目标与副本数的模拟回执。
     *
     * @param proposal 经审批服务校验的提案
     * @return 状态固定为 SIMULATED_EXECUTION 的回执
     */
    @Override
    public ActionExecutionResult execute(ActionProposal proposal) {
        Objects.requireNonNull(proposal, "提案不能为空");
        return new ActionExecutionResult(
                proposal.id(),
                ActionExecutionStatus.SIMULATED_EXECUTION,
                "模拟将 Topic %s 的 Consumer 副本数调整为 %d；未修改任何外部系统。"
                        .formatted(proposal.target(), proposal.replicas()),
                Instant.now()
        );
    }
}
