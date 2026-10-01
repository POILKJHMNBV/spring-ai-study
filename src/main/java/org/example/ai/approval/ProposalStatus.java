package org.example.ai.approval;

/**
 * 提案在进程内审批流程中的状态。
 *
 * <p>状态只由 {@link ApprovalService} 在同步保护下转换，模型和调用方均不能直接写入。</p>
 */
public enum ProposalStatus {
    /**
     * 等待可信应用侧人员审批。
     */
    PENDING_APPROVAL,
    /**
     * 已获审批，尚未请求执行。
     */
    APPROVED,
    /**
     * 已被拒绝，永远不能执行。
     */
    REJECTED,
    /**
     * 执行器正在处理；并发执行请求会等待当前转换完成。
     */
    EXECUTING,
    /**
     * 已执行一次。Day16 的执行结果只模拟操作。
     */
    EXECUTED,
    /**
     * 执行器发生异常；为避免不确定副作用被重复尝试，该状态不可重试。
     */
    EXECUTION_FAILED
}
