package org.example.ai.harness;

import lombok.extern.slf4j.Slf4j;
import org.example.ai.rag.RetrievedChunk;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.util.Assert;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 日志记录
 */
@Slf4j
public class TraceRecorder {

    private final List<TraceEvent> events = new CopyOnWriteArrayList<>();

    public enum EventType {
        MODEL,
        TOOL,
        CONTEXT,
        POLICY,
        STOP,
        RAG
    }

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

    public void recordModel(
            int step,
            int contextMessageCount,
            ChatResponse response,
            long elapsedMs
    ) {

        Assert.notNull(response, "response must not be null");
        Assert.notNull(response.getResult(), "response.result must not be null");

        Usage usage = response.getMetadata().getUsage();
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
                usage.getPromptTokens(),
                usage.getCompletionTokens(),
                usage.getTotalTokens(),
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

    private void add(TraceEvent event) {

        events.add(event);

        log.info(
                "TRACE step={} type={} name={} status={} elapsed={}ms input={} output={}",
                event.step(),
                event.type(),
                event.name(),
                event.status(),
                event.elapsedMs(),
                event.input(),
                event.output()
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