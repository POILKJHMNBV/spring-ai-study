package org.example.ai.approval;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 注册给 Agent 的 Day16 提案工具。
 *
 * <p>此对象只包含创建提案的方法。批准、拒绝、查询审计和执行接口均不在模型可见工具集中。</p>
 */
@Component
public class ActionProposalTools {
    /**
     * 保存提案并强制服务端校验的流程服务。
     */
    private final ApprovalService approvalService;

    /**
     * 创建提案工具并注入服务器端审批服务。
     *
     * @param approvalService 服务端提案和审批生命周期管理器
     */
    public ActionProposalTools(ApprovalService approvalService) {
        this.approvalService = Objects.requireNonNull(approvalService, "审批服务不能为空");
    }

    /**
     * 为允许的 Kafka Topic 创建 Consumer 扩容提案；本工具不审批也不执行动作。
     *
     * @param topic    需要扩容的 Topic；服务端只允许 order-topic
     * @param replicas 目标副本数；服务端只允许 2 到 10
     * @param reason   一句话原因；服务端限制长度并拒绝控制字符
     * @return 带服务端生成 ID 且强制要求人工审批的待审批提案
     */
    @Tool(description = "仅创建 Consumer 扩容的待审批提案，不会批准或执行动作。仅适用于有当前 Kafka 证据支持的扩容建议；目标 Topic 必须受服务器白名单允许，副本数由服务端限制。审批和执行必须由受信任应用侧人员另外完成。")
    public ActionProposal proposeScaleConsumer(
            @ToolParam(description = "要调整的 Kafka Topic，例如 order-topic") String topic,
            @ToolParam(description = "目标 Consumer 副本数，允许范围由服务端强制校验") int replicas,
            @ToolParam(description = "提出该动作的简短原因，必须基于当前诊断证据") String reason) {
        return approvalService.proposeScaleConsumer(topic, replicas, reason);
    }
}
