package org.example.ai.approval;

import java.time.Instant;
import java.util.Objects;

/**
 * 一次成功审批或拒绝的结果快照。
 *
 * @param proposalId 被处理的提案 ID
 * @param status 审批后状态，只可能是 APPROVED 或 REJECTED
 * @param actor 可信应用侧提供的审批者身份
 * @param occurredAt 决策时间
 */
public record ApprovalDecisionResult(
        String proposalId,
        ProposalStatus status,
        String actor,
        Instant occurredAt
) {
    /**
     * 验证审批结果快照，避免向调用方返回含糊或未完成的状态。
     *
     * @param proposalId 提案 ID
     * @param status 最终审批状态
     * @param actor 审批者身份
     * @param occurredAt 决策时间
     */
    public ApprovalDecisionResult {
        Objects.requireNonNull(proposalId, "提案 ID 不能为空");
        Objects.requireNonNull(status, "审批状态不能为空");
        Objects.requireNonNull(actor, "审批者不能为空");
        Objects.requireNonNull(occurredAt, "审批时间不能为空");
        if (status != ProposalStatus.APPROVED && status != ProposalStatus.REJECTED) {
            throw new IllegalArgumentException("审批结果状态必须是 APPROVED 或 REJECTED");
        }
    }
}
