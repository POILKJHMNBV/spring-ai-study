package org.example.ai.approval;

import java.time.Instant;
import java.util.Objects;

/**
 * 不可变的 Consumer 扩容提案。
 *
 * <p>创建后提案内容不会被审批流程改写。审批状态和审计事件单独存放在服务端进程内状态中，
 * 以便保留提案原文并让状态变化可追踪。</p>
 *
 * @param id 服务端生成的 UUID 提案标识
 * @param action 服务端白名单动作
 * @param target 经服务端 Topic 白名单校验的目标
 * @param replicas 经服务端范围校验的目标副本数
 * @param reason 限长后的提案原因
 * @param risk 服务端固定的风险等级
 * @param approvalRequired 服务端强制为 true 的审批要求
 * @param createdAt 提案创建时刻
 */
public record ActionProposal(
        String id,
        ActionType action,
        String target,
        int replicas,
        String reason,
        ActionRisk risk,
        boolean approvalRequired,
        Instant createdAt
) {
    /**
     * 校验不可变提案的所有必需字段，阻止无效对象绕过服务端工厂方法。
     *
     * @param id 服务端生成的 UUID 提案标识
     * @param action 已注册的动作类型
     * @param target 目标 Topic
     * @param replicas 目标 Consumer 副本数量
     * @param reason 提案原因
     * @param risk 服务端设置的风险等级
     * @param approvalRequired 是否要求审批
     * @param createdAt 创建时间
     */
    public ActionProposal {
        Objects.requireNonNull(id, "提案 ID 不能为空");
        Objects.requireNonNull(action, "动作类型不能为空");
        Objects.requireNonNull(target, "动作目标不能为空");
        Objects.requireNonNull(reason, "动作原因不能为空");
        Objects.requireNonNull(risk, "动作风险不能为空");
        Objects.requireNonNull(createdAt, "创建时间不能为空");
        if (id.isBlank() || target.isBlank() || reason.isBlank()) {
            throw new IllegalArgumentException("提案 ID、目标和原因不能为空白字符");
        }
        if (action != ActionType.SCALE_CONSUMER || !"order-topic".equals(target)
                || replicas < 2 || replicas > 10 || risk != ActionRisk.MEDIUM
                || reason.length() > 300 || reason.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("提案内容未通过 Day16 服务端动作策略");
        }
        if (!approvalRequired) {
            throw new IllegalArgumentException("Day16 的副作用动作必须要求人工审批");
        }
    }
}
