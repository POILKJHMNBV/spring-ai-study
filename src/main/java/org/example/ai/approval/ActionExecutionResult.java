package org.example.ai.approval;

import java.time.Instant;
import java.util.Objects;

/**
 * 执行器返回给可信应用侧的结果。
 *
 * @param proposalId 已审批并执行的提案 ID
 * @param status 执行状态；Day16 固定只能为 SIMULATED_EXECUTION
 * @param message 明确告知本次操作为模拟
 * @param executedAt 执行器生成回执的时刻
 */
public record ActionExecutionResult(
        String proposalId,
        ActionExecutionStatus status,
        String message,
        Instant executedAt
) {
    /**
     * 校验执行回执，防止 Day16 结果被误标成真实执行。
     *
     * @param proposalId 提案 ID
     * @param status 仅允许模拟执行状态
     * @param message 模拟结果说明
     * @param executedAt 回执时间
     */
    public ActionExecutionResult {
        Objects.requireNonNull(proposalId, "提案 ID 不能为空");
        Objects.requireNonNull(status, "执行状态不能为空");
        Objects.requireNonNull(message, "执行说明不能为空");
        Objects.requireNonNull(executedAt, "执行时间不能为空");
        if (status != ActionExecutionStatus.SIMULATED_EXECUTION) {
            throw new IllegalArgumentException("Day16 只允许 SIMULATED_EXECUTION");
        }
    }
}
