package org.example.ai.harness;

import org.example.ai.diagnosis.DiagnosisReport;

import java.util.List;

/**
 * Agent 执行结果：包含运行状态、最终回答、完成步数和全链路追踪。
 *
 * 字段说明：
 * <ul>
 *   <li>{@code status} — 运行状态：成功、步数超限、策略拒绝或失败</li>
 *   <li>{@code answer} — 最终回答或错误信息</li>
 *   <li>{@code completedSteps} — 实际执行的步数</li>
 *   <li>{@code trace} — 全链路追踪事件列表，用于调试和审计</li>
 *   <li>{@code report} — 成功执行时的结构化诊断报告；旧四参成功构造保留 null 以避免伪造诊断</li>
 * </ul>
 *
 * @param status Agent 运行状态
 * @param answer 面向人的回答或运行错误说明
 * @param completedSteps 实际进入的 Agent 步数
 * @param trace 全链路追踪事件列表
 * @param report 模型输出经契约解析后的诊断报告
 */
public record AgentRunResult(
        RunStatus status,
        String answer,
        int completedSteps,
        List<TraceRecorder.TraceEvent> trace,
        DiagnosisReport report
) {

    /**
     * 将缺少的追踪列表归一为空列表，并复制已有列表，保证 JSON 字段稳定且不可变。
     *
     * @param status Agent 运行状态
     * @param answer 面向人的回答或运行错误说明
     * @param completedSteps 实际进入的 Agent 步数
     * @param trace 全链路追踪事件列表
     * @param report 本次运行生成的结构化诊断报告；兼容旧成功构造时可以为空
     */
    public AgentRunResult {
        trace = trace == null ? List.of() : List.copyOf(trace);
    }

    /**
     * 保留旧的四参失败结果构造方式；旧成功调用不推断或伪造结构化诊断。
     *
     * @param status Agent 运行状态
     * @param answer 面向人的回答或运行错误说明
     * @param completedSteps 实际进入的 Agent 步数
     * @param trace 全链路追踪事件列表
     */
    public AgentRunResult(RunStatus status, String answer, int completedSteps,
                          List<TraceRecorder.TraceEvent> trace) {
        this(status, answer, completedSteps, trace, legacyReport(status));
    }

    /**
     * 为旧的失败构造调用补充固定安全报告，成功结果则继续保持未结构化状态。
     *
     * @param status Agent 运行状态
     * @return 失败占位报告，或旧成功结果适用的 null
     */
    private static DiagnosisReport legacyReport(RunStatus status) {
        return status == RunStatus.COMPLETED ? null : DiagnosisReport.failed();
    }

    /**
     * Agent 运行状态枚举。
     */
    public enum RunStatus {
        /** 正常完成：Agent 生成了最终回答。 */
        COMPLETED,
        /** 步数超限：达到 MAX_STEPS 仍未生成最终回答。 */
        STEP_LIMIT_EXCEEDED,
        /** 策略拒绝：工具调用被 {@link ExecutionPolicy} 拒绝。 */
        POLICY_REJECTED,
        /** 执行失败：LLM 调用、工具执行或工具发现失败。 */
        FAILED
    }
}
