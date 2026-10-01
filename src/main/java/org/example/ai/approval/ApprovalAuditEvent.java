package org.example.ai.approval;

/**
 * 审批生命周期的审计事件类型。
 *
 * <p>审核请求即使因为未知提案、无效状态或执行失败而被拒绝，也会留下对应事件。</p>
 */
public enum ApprovalAuditEvent {
    /**
     * 提案成功创建。
     */
    PROPOSED,
    /**
     * 提案参数未通过服务端动作策略。
     */
    PROPOSAL_ATTEMPT_REJECTED,
    /**
     * 重复的未决提案复用了已有 ID，避免工具回调重试制造多份审批记录。
     */
    DEDUPLICATED,
    /**
     * 审批尝试因提案状态或标识无效而被拒绝。
     */
    APPROVAL_ATTEMPT_REJECTED,
    /**
     * 执行尝试因提案状态、标识或执行者无效而被拒绝。
     */
    EXECUTION_ATTEMPT_REJECTED,
    /**
     * 可信应用侧批准了提案。
     */
    APPROVED,
    /**
     * 可信应用侧拒绝了提案。
     */
    REJECTED,
    /**
     * 模拟执行器完成一次执行。
     */
    EXECUTED,
    /**
     * 执行器抛出异常；提案进入终态，禁止重试以避免重复副作用。
     */
    EXECUTION_FAILED
}
