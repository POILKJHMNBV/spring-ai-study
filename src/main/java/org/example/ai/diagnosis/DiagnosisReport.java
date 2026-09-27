package org.example.ai.diagnosis;

import java.util.List;
import java.util.Objects;

/**
 * Agent 对一次故障排查形成的稳定结构化结果，同时提供面向人的展示文本。
 *
 * @param status 诊断状态
 * @param summary 对诊断结果的简要说明
 * @param facts 已观察到并标明来源的事实
 * @param hypotheses 基于事实提出的根因假设
 * @param nextActions 建议采取的后续动作
 * @param missingInformation 当前仍缺少的信息
 */
public record DiagnosisReport(
        DiagnosisStatus status,
        String summary,
        List<Fact> facts,
        List<Hypothesis> hypotheses,
        List<NextAction> nextActions,
        List<String> missingInformation
) {

    /**
     * 创建报告并复制所有集合，保证返回给 API 与 Memory 的内容不可被外部改写。
     *
     * @param status 诊断状态
     * @param summary 对诊断结果的简要说明
     * @param facts 已观察到并标明来源的事实
     * @param hypotheses 基于事实提出的根因假设
     * @param nextActions 建议采取的后续动作
     * @param missingInformation 当前仍缺少的信息
     */
    public DiagnosisReport {
        Objects.requireNonNull(status, "诊断状态不能为空");
        Objects.requireNonNull(summary, "诊断摘要不能为空");
        facts = List.copyOf(Objects.requireNonNull(facts, "事实列表不能为空"));
        hypotheses = List.copyOf(Objects.requireNonNull(hypotheses, "假设列表不能为空"));
        nextActions = List.copyOf(Objects.requireNonNull(nextActions, "后续动作列表不能为空"));
        missingInformation = List.copyOf(Objects.requireNonNull(missingInformation, "缺失信息列表不能为空"));
    }

    /**
     * 生成固定形状且不携带模型原文或底层异常内容的失败报告。
     *
     * @return 字段完整、状态为 FAILED 的安全占位报告
     */
    public static DiagnosisReport failed() {
        return new DiagnosisReport(
                DiagnosisStatus.FAILED,
                "未能生成可用的结构化诊断报告。",
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    /**
     * 将结构化诊断渲染为简洁文本，供 API 的 answer 字段与会话记忆展示。
     *
     * @return 保留状态、摘要和全部报告项目的多行展示文本
     */
    public String renderForDisplay() {
        StringBuilder display = new StringBuilder()
                .append("状态：").append(status.name())
                .append("\n摘要：").append(summary);

        appendSection(display, "已观察事实", facts.stream()
                .map(fact -> fact.statement() + "（来源：" + fact.source() + "）")
                .toList());
        appendSection(display, "根因假设", hypotheses.stream()
                .map(hypothesis -> {
                    String evidence = hypothesis.evidence().isEmpty()
                            ? "无列明证据"
                            : String.join("；", hypothesis.evidence());
                    return hypothesis.cause() + "（置信度：" + hypothesis.confidence()
                            + "；依据：" + evidence + "）";
                })
                .toList());
        appendSection(display, "下一步建议", nextActions.stream()
                .map(action -> action.description()
                        + (action.requiresApproval() ? "（建议执行前申请人工审批）" : "（模型建议无需人工审批）"))
                .toList());
        appendSection(display, "待补充信息", missingInformation);
        return display.toString();
    }

    /**
     * 把非空清单追加到报告展示文本中；空清单会被省略以保持阅读紧凑。
     *
     * @param display 正在构造的展示文本
     * @param title 清单标题
     * @param items 清单中的展示项目
     */
    private static void appendSection(StringBuilder display, String title, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        display.append("\n").append(title).append("：");
        for (String item : items) {
            display.append("\n- ").append(item);
        }
    }
}
