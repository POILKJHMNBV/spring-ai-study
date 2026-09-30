package org.example.ai.diagnosis;

import org.example.ai.harness.TraceRecorder;
import org.example.ai.rag.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 Day15 结构化报告中的业务置信度、证据引用和缺失信息边界。 */
class Day15OutputValidationTest {
    private static final String TOOL_JSON = "{\"healthy\":true,\"threadPoolActive\":8}";
    private static final String FACT = "{\"healthy\":true,\"threadPoolActive\":8}";
    private static final EvidenceCatalog CATALOG = EvidenceCatalog.fromTrace(List.of(
            new TraceRecorder.TraceEvent(1, TraceRecorder.EventType.TOOL, "getServiceStatus",
                    "{\"serviceName\":\"payment-service\"}", TOOL_JSON, 1,
                    null, null, null, "SUCCESS, attempt=1")), List.of());

    /** confidence 必须是有限数值且处于闭区间 [0, 1]。 */
    @Test
    void rejectsConfidenceOutsideClosedUnitInterval() {
        assertInvalid(report(-0.01, List.of(FACT), List.of(FACT)));
        assertInvalid(report(1.01, List.of(FACT), List.of(FACT)));
        assertValid(report(0.0, List.of(FACT), List.of(FACT)));
        assertValid(report(1.0, List.of(FACT), List.of(FACT)));
    }

    /** confidence 即使被写成 JSON 的 NaN 伪数字，也必须被严格 JSON 结构层拒绝。 */
    @Test
    void rejectsNanAsMalformedJsonNumber() {
        assertInvalid(report(Double.NaN, List.of(FACT), List.of(FACT)));
    }

    /** DIAGNOSED 假设必须完整引用已列出的事实，空事实或空证据不能支撑诊断。 */
    @Test
    void diagnosedHypothesisMustCiteExistingFactsExactly() {
        assertInvalid(report(0.8, List.of(), List.of(FACT)));
        assertInvalid(report(0.8, List.of(FACT), List.of("线程池即将耗尽")));
        assertInvalid(report(0.8, List.of(FACT), List.of()));
        assertValid(report(0.8, List.of(FACT), List.of(FACT)));
    }

    /** 事实来源必须来自本轮真实工具；模型不能用合法工具名包装未观察到的数值。 */
    @Test
    void rejectsForgedSourceAndInventedToolFact() {
        String forgedSource = reportWithFact("{\"healthy\":true,\"threadPoolActive\":8}",
                "guessServiceStatus", FACT, List.of());
        String inventedFact = reportWithFact("CPU 使用率为 99%", "getServiceStatus",
                "CPU 使用率为 99%", List.of());

        assertInvalid(forgedSource);
        assertInvalid(inventedFact);
    }

    /** missingInformation 不能把工具已返回的指标或其观测值标记为未知。 */
    @Test
    void rejectsMissingInformationThatContradictsObservedMetrics() {
        assertInvalid(reportWithMissing("threadPoolActive 尚未获取"));
        assertInvalid(reportWithMissing("threadPoolActive=8 仍未知"));
        assertValid(insufficientReport(List.of("需要最近五分钟的错误日志")));
    }

    /** 合法的 INSUFFICIENT_EVIDENCE 报告可以说明缺少的补充信息并留空诊断列表。 */
    @Test
    void acceptsWellFormedInsufficientEvidenceReport() {
        String raw = insufficientReport(List.of("需要最近五分钟的错误日志"));

        DiagnosisReport parsed = DiagnosisReportValidator.parseAndValidate(raw, CATALOG);

        assertEquals(DiagnosisStatus.INSUFFICIENT_EVIDENCE, parsed.status());
        assertEquals(List.of("需要最近五分钟的错误日志"), parsed.missingInformation());
    }

    /** 调用严格业务校验器并断言样例被接受。
     * @param raw 待校验的完整报告 JSON
     */
    private void assertValid(String raw) {
        assertDoesNotThrow(() -> DiagnosisReportValidator.parseAndValidate(raw, CATALOG));
    }

    /** 调用严格业务校验器并断言样例被拒绝。
     * @param raw 待校验的完整报告 JSON
     */
    private void assertInvalid(String raw) {
        assertThrows(IllegalArgumentException.class,
                () -> DiagnosisReportValidator.parseAndValidate(raw, CATALOG));
    }

    /** 构造引用给定事实和假设证据的 DIAGNOSED 报告 JSON。
     * @param confidence 根因假设置信度
     * @param facts 报告中列出的事实陈述
     * @param evidence 假设逐字引用的事实陈述
     * @return 可直接发送给报告校验器的 JSON
     */
    private String report(double confidence, List<String> facts, List<String> evidence) {
        String factObjects = facts.stream().map(value -> "{\"statement\":\"" + escape(value)
                + "\",\"source\":\"getServiceStatus\"}").reduce((a, b) -> a + "," + b).orElse("");
        String evidenceValues = evidence.stream().map(value -> "\"" + escape(value) + "\"")
                .reduce((a, b) -> a + "," + b).orElse("");
        return "{\"status\":\"DIAGNOSED\",\"summary\":\"检测到服务状态\",\"facts\":["
                + factObjects + "],\"hypotheses\":[{\"cause\":\"线程池压力\",\"confidence\":"
                + confidence + ",\"evidence\":[" + evidenceValues
                + "]}],\"nextActions\":[],\"missingInformation\":[]}";
    }

    /** 构造一条可以单独替换 source 和 statement 的报告事实。
     * @param statement 报告事实陈述
     * @param source 报告声明的证据来源
     * @param evidence 假设引用的事实陈述
     * @param missing 报告声明仍缺少的信息
     * @return 包含指定字段的 DIAGNOSED 报告 JSON
     */
    private String reportWithFact(String statement, String source, String evidence, List<String> missing) {
        return "{\"status\":\"DIAGNOSED\",\"summary\":\"检测到服务状态\",\"facts\":[{\"statement\":\""
                + escape(statement) + "\",\"source\":\"" + escape(source)
                + "\"}],\"hypotheses\":[{\"cause\":\"线程池压力\",\"confidence\":0.8,\"evidence\":[\""
                + escape(evidence) + "\"]}],\"nextActions\":[],\"missingInformation\":["
                + missing.stream().map(value -> "\"" + escape(value) + "\"")
                .reduce((a, b) -> a + "," + b).orElse("") + "]}";
    }

    /** 构造与已观察工具字段冲突的 missingInformation 样例。
     * @param missing 与已观察服务指标相冲突的缺失说明
     * @return 带指定 missingInformation 的诊断报告 JSON
     */
    private String reportWithMissing(String missing) {
        return reportWithFact(FACT, "getServiceStatus", FACT, List.of(missing));
    }

    /** 构造结构正确、但不提出无依据根因的证据不足报告。
     * @param missing 报告要求补充的信息列表
     * @return 证据不足状态的完整 JSON 报告
     */
    private String insufficientReport(List<String> missing) {
        return "{\"status\":\"INSUFFICIENT_EVIDENCE\",\"summary\":\"尚无足够证据\",\"facts\":[],"
                + "\"hypotheses\":[],\"nextActions\":[],\"missingInformation\":["
                + missing.stream().map(value -> "\"" + escape(value) + "\"")
                .reduce((a, b) -> a + "," + b).orElse("") + "]}";
    }

    /** 转义手写 JSON 样例中的反斜杠和引号。
     * @param value 待放入 JSON 字符串的内容
     * @return 可安全放入 JSON 字符串字面量的转义内容
     */
    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
