package org.example.ai.harness;

import lombok.extern.slf4j.Slf4j;
import org.example.ai.rag.RetrievedChunk;
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
        RAG
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

    /** 保存完整业务事件供返回值及 Eval 使用；运行日志只输出阶段、状态、耗时与 Token。 */
    private void add(TraceEvent event) {

        events.add(event);

        log.info(
                "TRACE step={} type={} name={} status={} elapsed={}ms promptTokens={}, completionTokens={}, totalTokens={}",
                event.step(),
                event.type(),
                event.name(),
                event.status(),
                event.elapsedMs(),
                event.promptTokens(),
                event.completionTokens(),
                event.totalTokens()
        );
    }

    public List<TraceEvent> snapshot() {
        return List.copyOf(events);
    }

    private String truncate(String value) {

        if (value == null) {
            return null;
        }

        int max = 1000;

        return value.length() <= max
                ? value
                : value.substring(0, max) + "...";
    }
}
