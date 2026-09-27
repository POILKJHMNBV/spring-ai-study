package org.example.ai.diagnosis;

import org.springframework.ai.converter.BeanOutputConverter;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

/**
 * 使用 Spring AI BeanOutputConverter 把模型 JSON 转为报告，并在转换前执行严格契约检查。
 * Spring AI 2.0.1 的默认转换器会清理代码围栏并忽略未知字段，因此此处先检查原始 JSON，
 * 避免这些宽松行为把结构不完整的响应转换成看似成功的对象。本类只校验 JSON 结构、字段、
 * null、数据类型和枚举；confidence 的数值范围、source 是否真实存在及 evidence 是否可溯源
 * 属于 Day15 业务验证内容，本类当前不做这些事实性判断。
 */
public final class DiagnosisReportValidator {

    private static final Set<String> REPORT_FIELDS = Set.of(
            "status", "summary", "facts", "hypotheses", "nextActions", "missingInformation");
    private static final Set<String> FACT_FIELDS = Set.of("statement", "source");
    private static final Set<String> HYPOTHESIS_FIELDS = Set.of("cause", "confidence", "evidence");
    private static final Set<String> NEXT_ACTION_FIELDS = Set.of("description", "requiresApproval");

    /**
     * 严格 Jackson mapper 只用于原始 JSON 结构预检及 BeanOutputConverter 的实际绑定。
     */
    private static final JsonMapper STRICT_JSON_MAPPER = JsonMapper.builder()
            .enable(
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    /**
     * Spring AI 官方结构化输出转换器；getFormat() 会为模型提供报告 JSON Schema。
     */
    private static final BeanOutputConverter<DiagnosisReport> OUTPUT_CONVERTER =
            new BeanOutputConverter<>(DiagnosisReport.class, STRICT_JSON_MAPPER, rawText -> rawText);

    /**
     * 工具类不允许构造实例。
     */
    private DiagnosisReportValidator() {
    }

    /**
     * 返回面向模型的完整输出说明：保留 Spring AI 生成的 JSON Schema，并补充无法从 DTO 类型
     * 自动表达的证据、来源、置信度和审批语义。
     *
     * @return 应附加到系统提示中的结构化 JSON Schema 与字段业务约束
     */
    public static String outputFormat() {
        return """
                以下语义约束与 JSON Schema 同时生效：
                - 先按需调用工具收集证据，再输出最终报告。每轮重新查询指每个新的用户请求，不指当前请求内的每次模型调用；本次请求已有成功工具结果时应复用，证据收集完成后立即返回报告，不要重复相同的成功查询。
                - facts.source 必须逐字使用本次排查实际返回的工具原名（例如 getServiceStatus）或当前上下文中真实出现的 RAG source；不得填写解释性中文或猜测来源。
                - hypotheses.evidence 只能引用 facts 中已经列出的已观察事实；不能把假设当成证据。
                - hypotheses.confidence 必须是 0 到 1 之间的 JSON 数值。
                - 证据不足以支持根因判断时使用 INSUFFICIENT_EVIDENCE，并填写仍需获取的 missingInformation。
                - 建议中包含写操作或其他副作用时必须设置 requiresApproval=true；该字段只表达建议的审批需求，不授权或执行操作。
                - 最终答案必须包含全部字段，空集合写作 []；只返回一个 JSON 对象，不添加额外字段、围栏或说明文字。

                %s
                """.formatted(OUTPUT_CONVERTER.getFormat());
    }

    /**
     * 校验原始模型响应的 JSON 结构、字段、null、类型和枚举后，通过 Spring AI 的
     * BeanOutputConverter 创建诊断报告；不验证置信度范围或来源真实性。
     *
     * @param rawJson 模型返回的原始 JSON 文本
     * @return 字段完整、类型正确并通过 Spring AI 转换的诊断报告
     * @throws IllegalArgumentException 当 JSON 缺失、格式不正确、字段无效或类型不符时抛出
     */
    public static DiagnosisReport parse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            throw invalid("模型未返回 JSON 内容");
        }

        JsonNode root;
        try {
            root = STRICT_JSON_MAPPER.readTree(rawJson);
        } catch (RuntimeException exception) {
            throw invalid("模型返回内容不是严格 JSON");
        }

        requireObjectFields(root, REPORT_FIELDS, "report");
        requireText(root.get("status"), "status");
        String status = root.get("status").stringValue();
        try {
            DiagnosisStatus.valueOf(status);
        } catch (IllegalArgumentException exception) {
            throw invalid("report.status 包含未知枚举值");
        }
        requireText(root.get("summary"), "summary");
        requireArray(root.get("facts"), "facts");
        for (int index = 0; index < root.get("facts").size(); index++) {
            JsonNode fact = root.get("facts").get(index);
            requireObjectFields(fact, FACT_FIELDS, "facts[" + index + "]");
            requireText(fact.get("statement"), "facts[" + index + "].statement");
            requireText(fact.get("source"), "facts[" + index + "].source");
        }

        requireArray(root.get("hypotheses"), "hypotheses");
        for (int index = 0; index < root.get("hypotheses").size(); index++) {
            JsonNode hypothesis = root.get("hypotheses").get(index);
            String path = "hypotheses[" + index + "]";
            requireObjectFields(hypothesis, HYPOTHESIS_FIELDS, path);
            requireText(hypothesis.get("cause"), path + ".cause");
            JsonNode confidence = hypothesis.get("confidence");
            if (confidence == null || !confidence.isNumber() || !Double.isFinite(confidence.doubleValue())) {
                throw invalid(path + ".confidence 必须是有限 JSON 数值");
            }
            requireArray(hypothesis.get("evidence"), path + ".evidence");
            requireTextArray(hypothesis.get("evidence"), path + ".evidence");
        }

        requireArray(root.get("nextActions"), "nextActions");
        for (int index = 0; index < root.get("nextActions").size(); index++) {
            JsonNode nextAction = root.get("nextActions").get(index);
            String path = "nextActions[" + index + "]";
            requireObjectFields(nextAction, NEXT_ACTION_FIELDS, path);
            requireText(nextAction.get("description"), path + ".description");
            JsonNode approval = nextAction.get("requiresApproval");
            if (approval == null || !approval.isBoolean()) {
                throw invalid(path + ".requiresApproval 必须是 JSON 布尔值");
            }
        }

        requireArray(root.get("missingInformation"), "missingInformation");
        requireTextArray(root.get("missingInformation"), "missingInformation");

        DiagnosisReport report;
        try {
            report = OUTPUT_CONVERTER.convert(rawJson);
        } catch (RuntimeException exception) {
            throw invalid("模型 JSON 无法转换为诊断报告");
        }
        if (report == null) {
            throw invalid("模型报告转换结果为空");
        }
        return report;
    }

    /**
     * 要求节点是 JSON 对象，且只包含契约规定的完整字段集合。
     *
     * @param node 待检查的 JSON 节点
     * @param requiredFields 该对象唯一允许的字段名集合
     * @param path 字段所在路径，用于诊断错误位置
     */
    private static void requireObjectFields(JsonNode node, Set<String> requiredFields, String path) {
        if (node == null || !node.isObject()) {
            throw invalid(path + " 必须是 JSON 对象");
        }
        Set<String> actualFields = node.properties().stream()
                .map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!actualFields.equals(requiredFields)) {
            throw invalid(path + " 缺少必需字段或包含未定义字段");
        }
        for (String field : requiredFields) {
            if (node.get(field) == null || node.get(field).isNull()) {
                throw invalid(path + "." + field + " 不能为空");
            }
        }
    }

    /**
     * 要求节点是 JSON 数组；null 和标量都不视为空数组。
     *
     * @param node 待检查的 JSON 节点
     * @param path 数组所在路径
     */
    private static void requireArray(JsonNode node, String path) {
        if (node == null || !node.isArray()) {
            throw invalid(path + " 必须是 JSON 数组");
        }
    }

    /**
     * 要求节点为 JSON 字符串，并禁止数组元素或字段使用 JSON null。
     *
     * @param node 待检查的 JSON 节点
     * @param path 字符串所在路径
     */
    private static void requireText(JsonNode node, String path) {
        if (node == null || !node.isString()) {
            throw invalid(path + " 必须是非 null JSON 字符串");
        }
    }

    /**
     * 检查数组中的每个元素都是 JSON 字符串，避免 Jackson 把其他标量强制转成文本。
     *
     * @param node 已确认是数组的 JSON 节点
     * @param path 数组所在路径
     */
    private static void requireTextArray(JsonNode node, String path) {
        for (int index = 0; index < node.size(); index++) {
            requireText(node.get(index), path + "[" + index + "]");
        }
    }

    /**
     * 创建不包含模型原文或异常详情的契约错误，调用方可以安全记录固定消息。
     *
     * @param reason 对错误类别的简短说明
     * @return 供调用方受控处理的契约异常
     */
    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException(reason);
    }
}
