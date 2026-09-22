package org.example.ai.eval;

import org.example.ai.harness.AgentRunResult;
import org.example.ai.harness.TraceRecorder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 防止合法百分比被误判，同时保留对编造数值的检测。 */
class DependencyEvidenceAuditTest {
    @Test
    void acceptsDerivedPercentagesButRejectsInventedMetrics() {
        var trace = List.of(new TraceRecorder.TraceEvent(1, TraceRecorder.EventType.TOOL,
                "getDependencyStatus", "{}", """
                {"database":{"activeConnections":49,"maxConnections":50},"rpc":{"timeoutRate":0.35}}
                """, 1, null, null, null, "SUCCESS, attempt=1"));
        var evaluator = new AgentEvaluator();
        var evalCase = AgentEvalCases.all().stream().filter(c -> c.id().equals("E04")).findFirst().orElseThrow();
        var valid = new AgentRunResult(AgentRunResult.RunStatus.COMPLETED, "连接池占用率 98%，RPC 超时率 35%", 2, trace);
        assertTrue(evaluator.evaluate(evalCase, valid, 1).evidenceFaithfulnessPass());
        var invalid = new AgentRunResult(AgentRunResult.RunStatus.COMPLETED, "RPC 超时率 99%", 2, trace);
        assertFalse(evaluator.evaluate(evalCase, invalid, 1).evidenceFaithfulnessPass());
    }

    @Test
    void doesNotTreatKnowledgeReferencesAndListNumbersAsMetrics() {
        var result = new AgentRunResult(AgentRunResult.RunStatus.COMPLETED,
                "Consumer Lag 数据不可用\n3. 补查服务\n连接池参见 [KB-2]\n4. 检查日志\n检查 P99/P95/timeout rate", 2, List.of());
        assertTrue(new AgentEvaluator().evaluate(AgentEvalCases.all().get(0), result, 1).evidenceFaithfulnessPass());
    }
}
