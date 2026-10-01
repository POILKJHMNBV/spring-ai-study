package org.example.ai.approval;

/**
 * 已审批动作的执行器契约。
 *
 * <p>Day16 的 Spring Bean 只有 {@link SimulatedActionExecutor}。审批服务只会在提案由 PENDING_APPROVAL
 * 成功审批为 APPROVED 后调用该接口，并对同一个提案最多调用一次。</p>
 */
public interface ActionExecutor {
    /**
     * 对一个已通过审批的不可变提案执行一次动作。
     *
     * @param proposal 经白名单校验且已批准的提案
     * @return Day16 的模拟执行结果
     * @throws RuntimeException 执行失败；提案随即进入不可重试的失败终态
     */
    ActionExecutionResult execute(ActionProposal proposal);
}
