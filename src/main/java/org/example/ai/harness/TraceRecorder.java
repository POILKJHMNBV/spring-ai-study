package org.example.ai.harness;

import lombok.extern.slf4j.Slf4j;
import org.example.ai.rag.RetrievedChunk;
import org.example.ai.security.ConversationSecurity;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.util.Assert;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Agent 全链路追踪记录器：记录 LLM 调用、工具执行、RAG 检索、策略拒绝等事件。
 *
 * <p>核心职责：
 * <ul>
 *   <li>记录每步的 LLM 调用：输入消息数、输出摘要、耗时、Token 用量</li>
 *   <li>记录每次工具调用：工具名、输入、输出、耗时、尝试次数、状态</li>
 *   <li>记录 RAG 检索结果：来源、排名、向量分、内容摘要</li>
 *   <li>记录上下文变化：工具执行前后消息数量</li>
 *   <li>记录策略拒绝和停止原因</li>
 * </ul>
 * </p>
 *
 * <p>设计要点：
 * <ul>
 *   <li>使用 {@link CopyOnWriteArrayList} 保证线程安全</li>
 *   <li>每次记录同时输出到日志，便于实时观察</li>
 *   <li>{@link #snapshot()} 返回不可变副本，防止外部修改</li>
 *   <li>长文本自动截断到 1000 字符，防止日志膨胀</li>
 * </ul>
 * </p>
 */
@Slf4j
public class TraceRecorder {

    private final List<TraceEvent> events = new CopyOnWriteArrayList<>();

    /**
     * 追踪事件类型枚举。
     */
    public enum EventType {
        /** LLM 模型调用。 */
        MODEL,
        /** 工具调用。 */
        TOOL,
        /** 上下文变化（工具执行后消息列表更新）。 */
        CONTEXT,
        /** 策略拒绝。 */
        POLICY,
        /** Agent 停止（正常完成、步数超限、失败等）。 */
        STOP,
        /** RAG 检索命中。 */
        RAG,
        /** 最终结构化报告的业务校验与一次修复流程。 */
        VALIDATION
    }

    /**
     * 单个追踪事件：记录步骤、类型、名称、输入、输出、耗时、Token 用量和状态。
     */
    public record TraceEvent(
            int step,
            EventType type,
            String name,
            String input,
            String output,
            long elapsedMs,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            String status
    ) {
    }

    public void recordRag(int rank, RetrievedChunk chunk) {
        add(new TraceEvent(
                0,
                EventType.RAG,
                chunk.source(),
                "rank=" + rank,
                """
                chunkIndex=%d
                score=%s
                content=%s
                """.formatted(
                        chunk.chunkIndex(),
                        chunk.score(),
                        truncate(chunk.text())
                ),
                0,
                null,
                null,
                null,
                "HIT"
        ));
    }

    /** 记录模型业务事件；缺少用量时保留 null，避免 EmptyUsage 的零值被误认作真实消耗。 */
    public void recordModel(
            int step,
            int contextMessageCount,
            ChatResponse response,
            long elapsedMs
    ) {

        Assert.notNull(response, "response must not be null");
        Assert.notNull(response.getResult(), "response.result must not be null");

        Usage usage = response.getMetadata().getUsage();
        // Spring AI 的 EmptyUsage 表示未提供用量；不能把其 0 当作真实零 token。
        boolean usageKnown = !(usage instanceof EmptyUsage);
        AssistantMessage output = response.getResult().getOutput();
        String resultSummary;
        if (output.hasToolCalls()) {
            resultSummary = output.getToolCalls()
                    .stream()
                    .map(call -> call.name() + "(" + call.arguments() + ")")
                    .toList()
                    .toString();
        }
        else {
            resultSummary = truncate(output.getText());
        }

        add(new TraceEvent(
                step,
                EventType.MODEL,
                "chatModel.call",
                "contextMessages=" + contextMessageCount,
                resultSummary,
                elapsedMs,
                usageKnown ? usage.getPromptTokens() : null,
                usageKnown ? usage.getCompletionTokens() : null,
                usageKnown ? usage.getTotalTokens() : null,
                "SUCCESS"
        ));
    }

    public void recordTool(
            int step,
            String toolName,
            String input,
            String output,
            long elapsedMs,
            int attempt,
            String status
    ) {
        add(new TraceEvent(
                step,
                EventType.TOOL,
                toolName,
                truncate(input),
                truncate(output),
                elapsedMs,
                null,
                null,
                null,
                status + ", attempt=" + attempt
        ));
    }

    public void recordContext(
            int step,
            int before,
            int after
    ) {

        add(new TraceEvent(
                step,
                EventType.CONTEXT,
                "conversationHistory",
                "messages=" + before,
                "messages=" + after,
                0,
                null,
                null,
                null,
                "UPDATED"
        ));
    }

    public void recordPolicyReject(
            int step,
            String message
    ) {

        add(new TraceEvent(
                step,
                EventType.POLICY,
                "ExecutionPolicy",
                null,
                message,
                0,
                null,
                null,
                null,
                "REJECTED"
        ));
    }

    /** 空正文补问属于模型恢复事件，不表示 Agent 已结束。 */
    public void recordEmptyAnswerRetry(int step) {
        add(new TraceEvent(step, EventType.MODEL, "chatModel.call", null,
                "模型正文为空，最多补问一次", 0, null, null, null, "EMPTY_ANSWER_RETRY"));
    }

    /**
     * 记录结构化诊断报告的输出契约验证结果。
     *
     * @param step 发生解析或业务校验的 Agent 步数
     * @param status 校验状态，使用 PASS 或 REJECTED
     * @param detail 简短的固定诊断信息，不应包含原始敏感工具数据
     */
    public void recordValidation(int step, String status, String detail) {
        add(new TraceEvent(step, EventType.VALIDATION, "OUTPUT_VALIDATION", null,
                truncate(detail), 0, null, null, null, status));
    }

    /**
     * 记录共享一次恢复预算的结构化报告修复状态。
     *
     * @param step 开始或结束修复时对应的 Agent 步数
     * @param status 修复状态：STARTED、SUCCEEDED、FAILED 或 LIMIT
     * @param detail 修复原因或受控结果摘要，不应包含原始敏感工具数据
     */
    public void recordRepair(int step, String status, String detail) {
        add(new TraceEvent(step, EventType.VALIDATION, "REPAIR", null,
                truncate(detail), 0, null, null, null, status));
    }

    public void recordStop(
            int step,
            String reason
    ) {

        add(new TraceEvent(
                step,
                EventType.STOP,
                "AgentRunner",
                null,
                reason,
                0,
                null,
                null,
                null,
                "STOP"
        ));
    }

    /**
     * 脱敏后保存事件，并只向运行日志输出适用于诊断的结构性字段。
     *
     * @param event 尚未进入轨迹列表的业务事件
     */
    private void add(TraceEvent event) {
        // Trace 会随 AgentRunResult 返回，也可能被测试或管理面读取；所有自由文本字段先统一脱敏。
        TraceEvent safeEvent = new TraceEvent(
                event.step(),
                event.type(),
                sanitizeNullable(event.name()),
                sanitizeNullable(event.input()),
                sanitizeNullable(event.output()),
                event.elapsedMs(),
                event.promptTokens(),
                event.completionTokens(),
                event.totalTokens(),
                sanitizeNullable(event.status())
        );
        events.add(safeEvent);

        log.info(
                "TRACE step={} type={} name={} status={} elapsed={}ms promptTokens={}, completionTokens={}, totalTokens={}",
                safeEvent.step(),
                safeEvent.type(),
                safeLogLabel(safeEvent.name()),
                safeLogLabel(safeEvent.status()),
                safeEvent.elapsedMs(),
                safeEvent.promptTokens(),
                safeEvent.completionTokens(),
                safeEvent.totalTokens()
        );
    }

    /**
     * 保留 null 语义并对自由文本字段统一执行凭据脱敏。
     *
     * @param value 可空的事件文本
     * @return null 原样保留；非 null 文本经敏感信息脱敏后返回
     */
    private String sanitizeNullable(String value) {
        return value == null ? null : ConversationSecurity.sanitizeSensitiveText(value);
    }

    /**
     * 清理日志标签中的控制字符并限制长度，防止不可信元数据伪造多行日志。
     *
     * @param value 已完成凭据脱敏的事件名或状态
     * @return 不含控制字符且不超过 128 个字符的日志字段；null 仍为 null
     */
    private String safeLogLabel(String value) {
        if (value == null) {
            return null;
        }
        String singleLine = value.replaceAll("[\\p{Cntrl}]", "_");
        return singleLine.length() <= 128 ? singleLine : singleLine.substring(0, 128);
    }

    public List<TraceEvent> snapshot() {
        return List.copyOf(events);
    }

    /**
     * 脱敏后把可返回轨迹的文本限制在固定长度，避免超大内容挤占内存和 API 响应。
     *
     * @param value 可能含敏感字段或不可信正文的事件文本
     * @return 已脱敏的原文或截断摘要；null 保持为 null
     */
    private String truncate(String value) {

        if (value == null) {
            return null;
        }

        // 先完整脱敏再截断，避免只保留 JSON 凭据的开头而丢失结束引号。
        String sanitized = ConversationSecurity.sanitizeSensitiveText(value);

        int max = 10000;

        return sanitized.length() <= max
                ? sanitized
                : sanitized.substring(0, max) + "...";
    }
}
