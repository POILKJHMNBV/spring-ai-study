package org.example.ai.eval;

import org.example.ai.diagnosis.DiagnosisReport;
import org.example.ai.diagnosis.DiagnosisStatus;
import org.example.ai.diagnosis.Fact;
import org.example.ai.diagnosis.Hypothesis;
import org.example.ai.diagnosis.NextAction;
import org.example.ai.harness.AgentRunResult;
import org.example.ai.harness.TraceRecorder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 Eval 根据诊断字段和本轮真实 Trace 评分，而非只搜索原始 JSON 文本。 */
class StructuredAgentEvaluatorTest {
    private final AgentEvaluator evaluator = new AgentEvaluator();
    private final AgentEvalCase e01 = AgentEvalCases.all().stream()
            .filter(evalCase -> "E01".equals(evalCase.id())).findFirst().orElseThrow();

    /** E01 只有根因假设命中目标、事实来自实时工具且假设带证据时才通过。 */
    @Test
    void e01RequiresDiagnosedCauseAndTraceBackedToolFact() {
        DiagnosisReport report = report(DiagnosisStatus.DIAGNOSED, "消费明显变慢",
                "线程池饱和导致请求排队", "活跃线程达到上限", "连接池无可用连接",
                "getServiceStatus");

        AgentEvalCaseResult result = evaluator.evaluate(e01,
                run("线程池饱和", report, trace("getServiceStatus", "getKafkaStatus", "queryErrorLogs")), 5);

        assertTrue(result.mainJudgmentPass());
        assertTrue(result.structuredOutputPass());
        assertTrue(result.sourceCitationPass());
    }

    /** 回答 JSON 中或下一步动作中的目标词不能掩盖根因假设与 E01 不符。 */
    @Test
    void e01IgnoresMisleadingAnswerAndActionWhenCauseDoesNotMatch() {
        DiagnosisReport report = report(DiagnosisStatus.DIAGNOSED, "消费明显变慢",
                "数据库连接池不可用", "数据库连接池等待变长", "连接池等待超时",
                "getServiceStatus");
        report = new DiagnosisReport(report.status(), report.summary(), report.facts(), report.hypotheses(),
                List.of(new NextAction("检查线程池饱和程度", false)), report.missingInformation());

        AgentEvalCaseResult result = evaluator.evaluate(e01,
                run("原始 JSON 文本误写了线程池饱和", report,
                        trace("getServiceStatus", "getKafkaStatus", "queryErrorLogs")), 5);

        assertFalse(result.mainJudgmentPass());
    }

    /** 缺少假设证据或事实来源不在实时 Trace 中时，E01 证据门槛必须失败。 */
    @Test
    void e01RejectsUnsupportedSourcesAndEmptyHypothesisEvidence() {
        DiagnosisReport noEvidence = new DiagnosisReport(DiagnosisStatus.DIAGNOSED, "线程池饱和",
                List.of(new Fact("当前线程池活跃", "inventedTool")),
                List.of(new Hypothesis("线程池饱和", 0.8, List.of())), List.of(), List.of());

        AgentEvalCaseResult result = evaluator.evaluate(e01,
                run("线程池饱和", noEvidence,
                        trace("getServiceStatus", "getKafkaStatus", "queryErrorLogs")), 5);

        assertFalse(result.sourceCitationPass());
        assertFalse(result.structuredOutputPass());
        assertFalse(result.overallPass());

        DiagnosisReport invalidConfidence = new DiagnosisReport(DiagnosisStatus.DIAGNOSED, "线程池饱和",
                List.of(new Fact("线程池达到上限", "getServiceStatus")),
                List.of(new Hypothesis("线程池饱和", 1.2, List.of("活跃线程达到最大值"))), List.of(), List.of());
        AgentEvalCaseResult confidenceResult = evaluator.evaluate(e01,
                run("线程池饱和", invalidConfidence,
                        trace("getServiceStatus", "getKafkaStatus", "queryErrorLogs")), 5);
        assertFalse(confidenceResult.structuredOutputPass(), "E01 置信度必须落在 0 到 1 之间");
    }

    /**
     * 构造一份字段完整的固定报告，按参数替换根因、证据及工具来源。
     *
     * @param status 报告诊断状态
     * @param summary 报告摘要
     * @param cause 根因假设内容
     * @param fact 实时观察事实
     * @param evidence 支持根因的事实证据
     * @param source 事实来源工具名称
     * @return 使用给定固定字段创建的诊断报告
     */
    private DiagnosisReport report(DiagnosisStatus status, String summary, String cause, String fact,
                                   String evidence, String source) {
        return new DiagnosisReport(status, summary,
                List.of(new Fact(fact, source)),
                List.of(new Hypothesis(cause, 0.8, List.of(evidence))),
                List.of(new NextAction("继续观察消费情况", false)), List.of());
    }

    /**
     * 把原始展示回答、结构化报告和 Trace 包装成单次 Agent 执行结果。
     *
     * @param answer 模型最终展示回答
     * @param report 已转换的诊断报告
     * @param trace 模拟本轮真实工具执行的轨迹
     * @return 固定的 Agent 运行结果
     */
    private AgentRunResult run(String answer, DiagnosisReport report, List<TraceRecorder.TraceEvent> trace) {
        return new AgentRunResult(AgentRunResult.RunStatus.COMPLETED, answer, 3, trace, report);
    }

    /**
     * 构造有固定工具名称的实时调用轨迹，不依赖外部服务。
     *
     * @param toolNames 本轮需要模拟的工具名称
     * @return 每个工具名称对应一条成功工具事件
     */
    private List<TraceRecorder.TraceEvent> trace(String... toolNames) {
        return java.util.Arrays.stream(toolNames)
                .map(name -> new TraceRecorder.TraceEvent(1, TraceRecorder.EventType.TOOL,
                        name, "{}", "{}", 1, null, null, null, "SUCCESS, attempt=1"))
                .toList();
    }
}
