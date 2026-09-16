package org.example.ai.harness;

import java.util.List;

public record AgentRunResult(
        RunStatus status,
        String answer,
        int completedSteps,
        List<TraceRecorder.TraceEvent> trace
) {

    public enum RunStatus {
        COMPLETED,
        STEP_LIMIT_EXCEEDED,
        POLICY_REJECTED,
        FAILED
    }
}