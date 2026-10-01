package org.example.ai.approval;

import java.time.Instant;
import java.util.Objects;

/**
 * 追加写入的审批审计记录。
 *
 * @param proposalId 提案 ID；未知或格式错误的 ID 也会保留为拒绝尝试的定位信息
 * @param event 发生的审批事件
 * @param actor 可信应用侧提供的操作身份，模型不能通过工具参数设置此值
 * @param occurredAt 事件发生时间
 * @param detail 不包含模型原始请求的简短服务端说明
 */
public record ApprovalAuditRecord(
        String proposalId,
        ApprovalAuditEvent event,
        String actor,
        Instant occurredAt,
        String detail
) {
    /**
     * 检查审计记录的必需字段，保证事件列表始终可供人工审查。
     *
     * @param proposalId 提案标识或格式错误请求的原始短值
     * @param event 事件类型
     * @param actor 操作主体
     * @param occurredAt 发生时刻
     * @param detail 固定说明
     */
    public ApprovalAuditRecord {
        Objects.requireNonNull(proposalId, "提案标识不能为空");
        Objects.requireNonNull(event, "审计事件不能为空");
        Objects.requireNonNull(actor, "审计主体不能为空");
        Objects.requireNonNull(occurredAt, "审计时间不能为空");
        Objects.requireNonNull(detail, "审计说明不能为空");
    }
}
