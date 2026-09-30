package org.example.ai.diagnosis;

import org.example.ai.harness.TraceRecorder;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 回归验证 Day15 缺失信息必须按完整指标语义匹配，不能由字段名子串误判。 */
class Day15ValidationRegressionTest {
    /** 将人工构造的证据不足报告序列化为校验器输入。 */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 已查询 payment-service 的当前服务快照，其中 CPU 数值确实已知。 */
    private static final String PAYMENT_SNAPSHOT =
            "{\"serviceName\":\"payment-service\",\"cpuPercent\":35.0,\"id\":\"req-7\"}";

    /** 查询只覆盖快照中的 CPU 值，不代表已取得其采样时间、趋势或升高原因。 */
    @Test
    void permitsMissingCpuSamplingWindowTrendAndCause() {
        assertDoesNotThrow(() -> validateMissing(
                "payment-service 的 cpuPercent 采样窗口、当前趋势及升高原因", paymentCatalog()));
    }

    /** 真实 E01 的历史对照需求提到了当前速率，但尚缺的是历史数据，不能被误拦。 */
    @Test
    void historicalBaselineForComparisonDoesNotRequestCurrentMetricAgain() {
        EvidenceCatalog evidence = catalog("{\"topic\":\"order-topic\",\"consumeRate\":2800}");
        assertDoesNotThrow(() -> validateMissing("历史基线数据用于对比当前消费速率变化", evidence));
        assertThrows(IllegalArgumentException.class,
                () -> validateMissing("历史基线和当前消费速率", evidence));
    }

    /** 真实 HTTP 反例：Topic 总积压不能代表各分区积压分布，仍须允许补查明细。 */
    @Test
    void aggregateLagDoesNotProvidePartitionDistribution() {
        EvidenceCatalog evidence = catalog("{\"topic\":\"order-topic\",\"lag\":125000}");
        assertDoesNotThrow(() -> validateMissing(
                "各 partition 的 Consumer Lag 分布情况（排查流量不均）", evidence));
        assertThrows(IllegalArgumentException.class, () -> validateMissing("当前 Lag", evidence));
    }

    /** 短字段名 id 不应匹配 requestId 等其他字段名中的字母子串。 */
    @Test
    void shortFieldNameDoesNotMatchLargerIdentifierName() {
        assertDoesNotThrow(() -> validateMissing("需要补充 requestId 指标及其生成规则", paymentCatalog()));
    }

    /** ASCII 字段短名 lag 不能错误命中 flag 配置中的字符子串。 */
    @Test
    void lagDoesNotMatchFlag() {
        assertDoesNotThrow(() -> validateMissing("还需要取得 flag 配置", catalog(
                "{\"serviceName\":\"payment-service\",\"lag\":12}")));
    }

    /** ASCII 字段 status 不能错误命中更长的 statusCode 名称。 */
    @Test
    void statusDoesNotMatchStatusCode() {
        assertDoesNotThrow(() -> validateMissing("还需要取得当前 statusCode", catalog(
                "{\"serviceName\":\"payment-service\",\"status\":\"UP\"}")));
    }

    /** 快照中已有细节字段时，仍须拒绝索取该字段，不能因它属于指标附属信息而整体豁免。 */
    @Test
    void refusesDetailsThatAreAlreadyPresentInTheSnapshot() {
        EvidenceCatalog detailedSnapshot = catalog("""
                {"serviceName":"payment-service","cpuPercent":35.0,
                 "samplingWindow":"5m","currentTrend":"stable","increaseCause":"deployment"}
                """);

        for (String knownDetail : List.of("samplingWindow", "currentTrend", "increaseCause")) {
            assertThrows(IllegalArgumentException.class,
                    () -> validateMissing("仍需获取 " + knownDetail, detailedSnapshot), knownDetail);
        }
    }

    /** 同一响应内 RPC 的窗口不能充当服务 CPU 的采样窗口，快照相同也必须区分对象路径。 */
    @Test
    void nestedComponentWindowDoesNotResolveCpuSamplingWindow() {
        EvidenceCatalog mixed = catalog("""
                {"serviceName":"payment-service","cpuPercent":35.0,
                 "rpc":{"samplingWindow":"5m"}}
                """);
        assertDoesNotThrow(() -> validateMissing("cpuPercent 的采样窗口", mixed));
    }

    /** 查询 payment-service 的指标不能阻止继续索取尚未查询的 inventory-service 指标。 */
    @Test
    void observedMetricForAnotherServiceDoesNotResolveRequestedServiceMetric() {
        assertDoesNotThrow(() -> validateMissing("inventory-service 当前 CPU 使用率", paymentCatalog()));
    }

    /** 明确索取已经观测到的当前 CPU 指标仍然属于矛盾，避免修复时放宽真实冲突边界。 */
    @Test
    void stillRejectsAnExplicitRequestForTheKnownCurrentCpuMetric() {
        assertThrows(IllegalArgumentException.class,
                () -> validateMissing("payment-service 当前 cpuPercent", paymentCatalog()));
        assertThrows(IllegalArgumentException.class,
                () -> validateMissing("缺少当前 CPU 使用率及其升高原因", paymentCatalog()));
        assertThrows(IllegalArgumentException.class,
                () -> validateMissing("历史 CPU 基线和当前 CPU 使用率", paymentCatalog()));
    }

    /** TraceRecorder 的实际记录边界必须保留数千字符的完整 JSON，以便目录 ID 可引用。 */
    @Test
    void evidenceBetweenOneAndEightThousandCharactersSurvivesRealTraceRecording() {
        String detail = "x".repeat(3_000);
        String snapshot = MAPPER.writeValueAsString(Map.of(
                "serviceName", "payment-service", "detail", detail));
        TraceRecorder trace = new TraceRecorder();
        trace.recordTool(1, "getServiceStatus", "{\"serviceName\":\"payment-service\"}",
                snapshot, 1L, 1, "SUCCESS");
        String recorded = trace.snapshot().get(0).output();
        EvidenceCatalog catalog = EvidenceCatalog.fromTrace(trace.snapshot(), List.of());

        assertTrue(recorded.length() > 1_000 && recorded.length() < 8_000);
        assertEquals(snapshot, recorded);
        DiagnosisReport report = validateToolReference("TOOL-1", "getServiceStatus", "TOOL-1", catalog);
        assertEquals(snapshot, report.facts().get(0).statement());
        assertEquals(List.of(snapshot), report.hypotheses().get(0).evidence());
    }

    /** TOOL ID 必须按来源解析，返回给调用方的事实和假设证据都展开为真实完整快照。 */
    @Test
    void expandsValidToolIdInFactsAndHypothesisEvidence() {
        DiagnosisReport report = validateToolReference("TOOL-1", "getServiceStatus", "TOOL-1",
                paymentCatalog());

        assertEquals(PAYMENT_SNAPSHOT, report.facts().get(0).statement());
        assertEquals(List.of(PAYMENT_SNAPSHOT), report.hypotheses().get(0).evidence());
    }

    /** 真实模型可能混用旧完整事实与新编号引用；同来源同快照的引用应展开到已列出的事实。 */
    @Test
    void fullSnapshotFactCanBeCitedByItsExactToolId() {
        DiagnosisReport report = validateToolReference(PAYMENT_SNAPSHOT, "getServiceStatus", "TOOL-1",
                paymentCatalog());
        assertEquals(List.of(PAYMENT_SNAPSHOT), report.hypotheses().get(0).evidence());
    }

    /** 目录里存在编号仍不足以构成假设证据；报告必须实际列出该编号绑定的快照。 */
    @Test
    void toolIdCannotCiteAnotherSnapshotMissingFromFacts() {
        TraceRecorder trace = new TraceRecorder();
        trace.recordTool(1, "getServiceStatus", "{}", PAYMENT_SNAPSHOT, 1L, 1, "SUCCESS");
        trace.recordTool(2, "getServiceStatus", "{}",
                PAYMENT_SNAPSHOT.replace("35.0", "70.0"), 1L, 1, "SUCCESS");
        EvidenceCatalog catalog = EvidenceCatalog.fromTrace(trace.snapshot(), List.of());
        assertThrows(IllegalArgumentException.class, () -> validateToolReference(
                PAYMENT_SNAPSHOT, "getServiceStatus", "TOOL-2", catalog));
    }

    /** 引用有效 ID 时仍须声明产生该 ID 的真实工具名。 */
    @Test
    void rejectsToolIdPairedWithWrongSource() {
        assertThrows(IllegalArgumentException.class, () -> validateToolReference(
                "TOOL-1", "getDependencyStatus", "TOOL-1", paymentCatalog()));
    }

    /** 目录之外的 ID 不能凭格式看似正确就构成事实。 */
    @Test
    void rejectsUnknownToolId() {
        assertThrows(IllegalArgumentException.class, () -> validateToolReference(
                "TOOL-2", "getServiceStatus", "TOOL-2", paymentCatalog()));
    }

    /** 后续请求没有重新取得工具证据时，不能沿用前一请求中的 TOOL-1。 */
    @Test
    void rejectsToolIdWithoutEvidenceInCurrentRun() {
        assertThrows(IllegalArgumentException.class, () -> validateToolReference(
                "TOOL-1", "getServiceStatus", "TOOL-1", EvidenceCatalog.fromTrace(List.of(), List.of())));
    }

    /**
     * 将单条缺失信息放入合法的证据不足报告并调用生产业务校验器。
     *
     * @param missing 模型声称仍需获取的信息
     * @param catalog 仅含 payment-service 成功快照的本次证据目录
     */
    private static void validateMissing(String missing, EvidenceCatalog catalog) {
        String raw = MAPPER.writeValueAsString(Map.of(
                "status", "INSUFFICIENT_EVIDENCE",
                "summary", "需要补充信息以完成排查",
                "facts", List.of(),
                "hypotheses", List.of(),
                "nextActions", List.of(),
                "missingInformation", List.of(missing)));
        DiagnosisReportValidator.parseAndValidate(raw, catalog);
    }

    /**
     * 构造一条通过目录 ID 引用工具快照的诊断报告。
     *
     * @param statementId facts.statement 中的工具目录 ID
     * @param source 报告声明的工具来源名称
     * @param evidenceId hypotheses.evidence 中的目录 ID
     * @param catalog 本次运行实际生成的证据目录
     * @return 校验后由生产代码返回的报告对象
     */
    private static DiagnosisReport validateToolReference(String statementId, String source,
                                                          String evidenceId, EvidenceCatalog catalog) {
        String raw = MAPPER.writeValueAsString(Map.of(
                "status", "DIAGNOSED",
                "summary", "依据服务快照形成待验证假设",
                "facts", List.of(Map.of("statement", statementId, "source", source)),
                "hypotheses", List.of(Map.of("cause", "服务可能存在资源压力", "confidence", 0.6,
                        "evidence", List.of(evidenceId))),
                "nextActions", List.of(),
                "missingInformation", List.of()));
        return DiagnosisReportValidator.parseAndValidate(raw, catalog);
    }

    /**
     * 构建本次仅成功查询 payment-service 的工具证据目录。
     *
     * @return 带完整原始响应及成功状态的证据目录
     */
    private static EvidenceCatalog paymentCatalog() {
        return catalog(PAYMENT_SNAPSHOT);
    }

    /**
     * 使用真实追踪事件把给定的工具快照放入本次证据目录。
     *
     * @param snapshot 成功服务查询返回的完整 JSON
     * @return 仅包含该次工具调用的证据目录
     */
    private static EvidenceCatalog catalog(String snapshot) {
        TraceRecorder.TraceEvent event = new TraceRecorder.TraceEvent(
                1, TraceRecorder.EventType.TOOL, "getServiceStatus",
                "{\"serviceName\":\"payment-service\"}", snapshot,
                1L, null, null, null, "SUCCESS, attempt=1");
        return EvidenceCatalog.fromTrace(List.of(event), List.of());
    }
}
