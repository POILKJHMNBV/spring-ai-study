package org.example.ai.diagnosis;

import org.example.ai.harness.TraceRecorder;
import org.example.ai.rag.RetrievedChunk;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 主 Agent 独立编写的 Day15 证据边界测试，不使用生产目录自动生成预期答案。
 * 人工构造错指标、错实体和失败来源，防止校验仅检查数字出现或来源名称存在。
 */
class Day15EvidenceBoundaryTest {
    /** 测试序列化器只负责将人工编排的报告转换成输入 JSON。 */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    /** 两个指标的值不同，用于证明字段和值必须成对绑定。 */
    private static final String SNAPSHOT =
            "{\"serviceName\":\"payment-service\",\"cpuPercent\":35.0,\"memoryPercent\":55.0}";

    /** 对象键顺序和无意义空白不改变事实内容，合法报告应继续通过。 */
    @Test
    void reorderedObjectStillRepresentsTheSameEvidence() {
        String reordered = "{ \"memoryPercent\":55.0, \"cpuPercent\":35.0, \"serviceName\":\"payment-service\" }";
        assertDoesNotThrow(() -> validate(reordered, "getServiceStatus", catalog("SUCCESS, attempt=1", SNAPSHOT)));
    }

    /** 数字都来自真实工具，但调换 CPU 和内存的对应关系必须拒绝。 */
    @Test
    void existingNumbersCannotBeMovedBetweenMetrics() {
        String forged = "{\"serviceName\":\"payment-service\",\"cpuPercent\":55.0,\"memoryPercent\":35.0}";
        assertThrows(IllegalArgumentException.class,
                () -> validate(forged, "getServiceStatus", catalog("SUCCESS, attempt=1", SNAPSHOT)));
    }

    /** 同样的指标值不能移植到没有查询过的服务实体上。 */
    @Test
    void existingMetricsCannotBeAttributedToAnotherEntity() {
        assertThrows(IllegalArgumentException.class, () -> validate(
                SNAPSHOT.replace("payment-service", "inventory-service"),
                "getServiceStatus", catalog("SUCCESS, attempt=1", SNAPSHOT)));
    }

    /** 工具查询失败，即使错误正文包含看似有效的指标也不构成成功证据。 */
    @Test
    void failedToolCannotAuthorizeItsPayload() {
        for (String status : List.of("TIMEOUT, attempt=1", "AUTH_FAILURE, attempt=1", "UNSUCCESSFUL")) {
            assertThrows(IllegalArgumentException.class,
                    () -> validate(SNAPSHOT, "getServiceStatus", catalog(status, SNAPSHOT)), status);
        }
    }

    /** 截断预览不能被重新包装为一份完整工具事实。 */
    @Test
    void truncatedPayloadDoesNotBecomeCompleteEvidence() {
        assertThrows(IllegalArgumentException.class, () -> validate(SNAPSHOT, "getServiceStatus",
                catalog("SUCCESS, attempt=1", "[TOOL_RESULT_TRUNCATED]\npreview:\n" + SNAPSHOT)));
    }

    /** 调用过一个工具不代表模型可以引用其他未调用工具作为来源。 */
    @Test
    void realPayloadDoesNotAuthorizeAnInventedSource() {
        assertThrows(IllegalArgumentException.class, () -> validate(SNAPSHOT,
                "getDependencyStatus", catalog("SUCCESS, attempt=1", SNAPSHOT)));
    }

    /** 不同调用返回的字段不能拼接成从未实际出现过的快照。 */
    @Test
    void separateSnapshotsCannotBeSpliced() {
        String other = SNAPSHOT.replace("35.0", "45.0").replace("55.0", "65.0");
        EvidenceCatalog evidence = EvidenceCatalog.fromTrace(List.of(
                toolEvent("SUCCESS, attempt=1", SNAPSHOT),
                toolEvent("SUCCESS, attempt=1", other)), List.of());
        assertThrows(IllegalArgumentException.class,
                () -> validate(SNAPSHOT.replace("55.0", "65.0"), "getServiceStatus", evidence));
    }

    /** 知识库说明只能作知识引用，没有实时工具结果时不能单独确认当前故障。 */
    @Test
    void knowledgeAloneCannotDiagnoseTheCurrentEnvironment() {
        String quote = "CPU 可能达到 99%，需要查询实时指标。";
        EvidenceCatalog evidence = EvidenceCatalog.fromTrace(List.of(), List.of(chunk(quote)));
        assertThrows(IllegalArgumentException.class,
                () -> validate("知识引用：" + quote, "cpu-guide.md", evidence));
    }

    /** 伪造知识引用内容，即便文件名真实存在也必须被拒绝。 */
    @Test
    void realKnowledgeSourceCannotAuthorizeInventedText() {
        EvidenceCatalog evidence = EvidenceCatalog.fromTrace(
                List.of(toolEvent("SUCCESS, attempt=1", SNAPSHOT)), List.of(chunk("检查 CPU 指标。")));
        String raw = MAPPER.writeValueAsString(Map.of(
                "status", "INSUFFICIENT_EVIDENCE", "summary", "仍需实时数据",
                "facts", List.of(Map.of("statement", "知识引用：数据库已经宕机。", "source", "cpu-guide.md")),
                "hypotheses", List.of(), "nextActions", List.of(), "missingInformation", List.of("数据库连接记录")));
        assertThrows(IllegalArgumentException.class,
                () -> DiagnosisReportValidator.parseAndValidate(raw, evidence));
    }

    /** 重复 JSON 键具有歧义，目录不能把最后一个值默默当成可信完整快照。 */
    @Test
    void ambiguousToolJsonIsNotTrusted() {
        String duplicated = "{\"serviceName\":\"payment-service\",\"cpuPercent\":99.0,\"cpuPercent\":35.0,\"memoryPercent\":55.0}";
        assertThrows(IllegalArgumentException.class,
                () -> validate(SNAPSHOT, "getServiceStatus", catalog("SUCCESS, attempt=1", duplicated)));
    }

    /** 额外尾随 JSON 不能在构造证据目录时被静默忽略。 */
    @Test
    void trailingToolJsonIsNotTrusted() {
        assertThrows(IllegalArgumentException.class,
                () -> validate(SNAPSHOT, "getServiceStatus", catalog("SUCCESS, attempt=1", SNAPSHOT + " {}")));
    }

    /** 缺失信息列表本身即声明尚未获取，不能依赖额外的“未知”关键字才发现冲突。 */
    @Test
    void alreadyObservedMetricCannotBeListedAsMissing() {
        for (String missing : List.of("CPU 使用率", "cpuPercent未知", "需要 CPU 使用率")) {
            assertThrows(IllegalArgumentException.class,
                    () -> validateMissing(missing, catalog("SUCCESS, attempt=1", SNAPSHOT)), missing);
        }
    }

    /** 仅有当前快照不能证明已获得历史基线，模型可以要求补充历史 CPU 数据。 */
    @Test
    void currentSnapshotDoesNotPretendToProvideHistoricalBaseline() {
        assertDoesNotThrow(() -> validateMissing("历史 CPU 使用率基线", catalog("SUCCESS, attempt=1", SNAPSHOT)));
    }

    /** 工具显式给出 null 代表缺失，不能据此阻止模型继续要求该指标。 */
    @Test
    void nullMetricDoesNotBecomeAnObservedValue() {
        assertDoesNotThrow(() -> validateMissing("cpuPercent 未获取",
                catalog("SUCCESS, attempt=1", "{\"cpuPercent\":null}")));
    }

    /** 数据库 P99 已知不等于 RPC P99 已知，两个嵌套路径不能只按末尾字段名匹配。 */
    @Test
    void knownDatabaseLatencyDoesNotResolveMissingRpcLatency() {
        EvidenceCatalog evidence = catalog("SUCCESS, attempt=1", "{\"database\":{\"p99Ms\":2500.0}}");
        assertDoesNotThrow(() -> validateMissing("rpc.p99Ms 未获取", evidence));
    }

    /** 必须存在证据是 DIAGNOSED 的规则；明确证据不足的占位假设不应被当作确认诊断。 */
    @Test
    void insufficientEvidenceMayExplicitlyDescribeAnUnconfirmedHypothesis() {
        String raw = MAPPER.writeValueAsString(Map.of(
                "status", "INSUFFICIENT_EVIDENCE", "summary", "无法确定原因",
                "facts", List.of(), "hypotheses", List.of(Map.of(
                        "cause", "原因尚未确定", "confidence", 0.0, "evidence", List.of())),
                "nextActions", List.of(), "missingInformation", List.of("具体服务名称")));
        assertDoesNotThrow(() -> DiagnosisReportValidator.parseAndValidate(raw,
                EvidenceCatalog.fromTrace(List.of(), List.of())));
    }

    /** FAILED 状态不能使其携带的伪造事实绕过校验并泄露到最终 API 报告。 */
    @Test
    void failedReportDoesNotBypassSourceValidation() {
        String raw = MAPPER.writeValueAsString(Map.of(
                "status", "FAILED", "summary", "排查失败",
                "facts", List.of(Map.of("statement", SNAPSHOT, "source", "getServiceStatus")),
                "hypotheses", List.of(), "nextActions", List.of(), "missingInformation", List.of()));
        assertThrows(IllegalArgumentException.class, () -> DiagnosisReportValidator.parseAndValidate(raw,
                EvidenceCatalog.fromTrace(List.of(), List.of())));
    }

    /**
     * 创建证据不足报告，单独验证缺失信息与实际工具目录的关系。
     *
     * @param missing 模型声明仍缺少的信息
     * @param evidence 本次实际工具证据
     */
    private static void validateMissing(String missing, EvidenceCatalog evidence) {
        String raw = MAPPER.writeValueAsString(Map.of(
                "status", "INSUFFICIENT_EVIDENCE", "summary", "需要补充证据",
                "facts", List.of(), "hypotheses", List.of(), "nextActions", List.of(),
                "missingInformation", List.of(missing)));
        DiagnosisReportValidator.parseAndValidate(raw, evidence);
    }

    /**
     * 为一个事实创建有证据假设的最小 DIAGNOSED 报告，交给真实校验器。
     *
     * @param statement 人工构造的事实陈述，不从生产目录读取
     * @param source 声明的来源名称
     * @param evidence 本次执行实际可用的证据目录
     */
    private static void validate(String statement, String source, EvidenceCatalog evidence) {
        String raw = MAPPER.writeValueAsString(Map.of(
                "status", "DIAGNOSED", "summary", "依据工具快照提出假设",
                "facts", List.of(Map.of("statement", statement, "source", source)),
                "hypotheses", List.of(Map.of("cause", "待验证原因", "confidence", 0.7,
                        "evidence", List.of(statement))),
                "nextActions", List.of(), "missingInformation", List.of()));
        DiagnosisReportValidator.parseAndValidate(raw, evidence);
    }

    /**
     * 使用人工工具事件构造单次执行的证据目录。
     *
     * @param status 真实工具执行状态
     * @param output 该次调用返回的正文
     * @return 仅包含此事件的证据目录
     */
    private static EvidenceCatalog catalog(String status, String output) {
        return EvidenceCatalog.fromTrace(List.of(toolEvent(status, output)), List.of());
    }

    /**
     * 构造与 GuardedToolCallback 相同形状的工具执行事件。
     *
     * @param status 工具执行状态
     * @param output 工具返回正文
     * @return 保留参数和完整正文的工具事件
     */
    private static TraceRecorder.TraceEvent toolEvent(String status, String output) {
        return new TraceRecorder.TraceEvent(1, TraceRecorder.EventType.TOOL, "getServiceStatus",
                "{\"serviceName\":\"payment-service\"}", output, 1L, null, null, null, status);
    }

    /**
     * 创建本次实际检索命中的测试知识片段。
     *
     * @param text 知识片段的真实正文
     * @return 含确定来源和正文的检索结果
     */
    private static RetrievedChunk chunk(String text) {
        return new RetrievedChunk("cpu-guide.md", "CPU 排查", "service", "检查步骤", "day15-cpu", 0, 0.9, text);
    }
}
