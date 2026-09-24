package org.example.ai.harness;

import java.util.List;

/**
 * Agent 执行结果：包含运行状态、最终回答、完成步数和全链路追踪。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code status} — 运行状态：成功、步数超限、策略拒绝或失败</li>
 *   <li>{@code answer} — 最终回答或错误信息</li>
 *   <li>{@code completedSteps} — 实际执行的步数</li>
 *   <li>{@code trace} — 全链路追踪事件列表，用于调试和审计</li>
 * </ul>
 * </p>
 */
public record AgentRunResult(
        RunStatus status,
        String answer,
        int completedSteps,
        List<TraceRecorder.TraceEvent> trace
) {

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