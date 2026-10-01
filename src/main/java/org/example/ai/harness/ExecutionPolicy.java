package org.example.ai.harness;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

/**
 * 工具调用安全策略：在工具执行前校验调用合法性，防止越权、滥用或死循环。
 *
 * <p>校验规则：
 * <ul>
 *   <li>工具总量限制：单次 Agent 请求最多 8 次工具调用，防止资源滥用</li>
 *   <li>工具白名单：四个只读工具和一个只创建待审批提案的工具；审批与执行入口不在白名单</li>
 *   <li>参数白名单：service 和 topic 参数必须在允许列表中</li>
 *   <li>重复调用检测：连续完全相同的工具调用会被拒绝，防止死循环</li>
 * </ul>
 * </p>
 *
 * <p>设计要点：
 * <ul>
 *   <li>{@link RunState} 维护单次请求的累计状态，跨步骤传递</li>
 *   <li>校验失败抛出 {@link PolicyViolationException}，由 AgentRunner 捕获并返回受控结果</li>
 *   <li>与 {@link AgentRunner#MAX_STEPS} 互补：MAX_STEPS 限制循环次数，本类限制单次响应的工具调用总量</li>
 * </ul>
 * </p>
 */
@Component
public class ExecutionPolicy {
    /**
     * 单次 Agent 请求允许的最大 Tool Call 数量。
     *
     * <p>与 {@code MAX_STEPS} 限制解决的问题不同：
     * MAX_STEPS 限制模型循环次数；本常量限制一次响应同时申请多个 Tool 时的总量。
     * 两者互补，共同防止资源滥用。</p>
     */
    private static final int MAX_TOOL_CALLS_PER_RUN = 8;

    /**
     * 工具白名单：只允许调用这些工具，防止 LLM 幻觉调用不存在的工具。
     */
    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "getKafkaStatus",
            "getServiceStatus",
            "getDependencyStatus",
            "queryErrorLogs",
            "proposeScaleConsumer"
    );

    /**
     * 服务白名单：只允许查询这些服务，防止越权访问其他服务指标。
     */
    private static final Set<String> ALLOWED_SERVICES = Set.of(
            "payment-service"
    );

    /**
     * Topic 白名单：只允许查询这些 Kafka Topic，防止越权访问其他 Topic。
     */
    private static final Set<String> ALLOWED_TOPICS = Set.of(
            "order-topic"
    );

    /**
     * 提案参数额外拒绝重复字段和尾随 JSON，避免策略解析与工具回调解释不同。
     */
    private static final ObjectMapper STRICT_TOOL_ARGUMENT_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .build();
    /**
     * 兼容已有只读工具的参数解析器。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建一次 Agent 请求专用的策略状态。
     *
     * @return 计数、连续重复检测状态均独立于其他请求的新状态对象
     */
    public RunState newRunState() {
        return new RunState();
    }

    /**
     * 在调用工具前校验该模型响应中的全部工具调用，并累计本次运行的调用数量。
     *
     * @param toolCalls 当前模型响应请求的工具调用列表
     * @param state     本次 Agent 请求共享的累计策略状态
     * @throws PolicyViolationException 工具名、参数、调用数量或重复调用不符合策略时抛出
     */
    public void check(List<AssistantMessage.ToolCall> toolCalls, RunState state) {

        /*
         * 一个模型响应可能一次产生多个 Tool Call，
         * 因此不能只依赖 Agent 的 step 数量。
         */
        int nextTotalToolCalls = state.totalToolCalls + toolCalls.size();

        if (nextTotalToolCalls > MAX_TOOL_CALLS_PER_RUN) {
            throw new PolicyViolationException("单次 Agent 请求的 Tool 调用数量超过限制: " + MAX_TOOL_CALLS_PER_RUN);
        }

        state.totalToolCalls = nextTotalToolCalls;

        for (AssistantMessage.ToolCall call : toolCalls) {

            String toolName = call.name();

            // 1. Tool 白名单
            if (!ALLOWED_TOOLS.contains(toolName)) {
                throw new PolicyViolationException("Tool not allowed: " + toolName);
            }

            JsonNode arguments = "proposeScaleConsumer".equals(toolName)
                    ? parseScaleProposalArguments(call.arguments())
                    : parseArguments(call.arguments());

            // 2. 参数权限
            switch (toolName) {
                case "getKafkaStatus" -> {

                    String topic = arguments.path("topic").asString();

                    if (!ALLOWED_TOPICS.contains(topic)) {
                        throw new PolicyViolationException("Topic not allowed: " + topic);
                    }
                }

                case "getServiceStatus", "getDependencyStatus", "queryErrorLogs" -> {

                    String serviceName = arguments.path("serviceName").asString();

                    if (!ALLOWED_SERVICES.contains(serviceName)) {
                        throw new PolicyViolationException("Service not allowed: " + serviceName);
                    }
                }

                case "proposeScaleConsumer" -> checkScaleProposalArguments(arguments);

                default -> throw new PolicyViolationException("Unexpected tool: " + toolName);
            }

            // 3. 连续重复调用检测
            String signature = toolName + ":" + arguments;

            if (signature.equals(state.lastToolSignature)) {
                throw new PolicyViolationException("Repeated tool call detected: " + signature);
            }

            state.lastToolSignature = signature;
        }
    }

    /**
     * 解析已有只读工具参数，并为策略异常保留底层解析原因但不回显原始输入。
     *
     * @param arguments 工具回调收到的 JSON 参数文本
     * @return Jackson JSON 节点
     * @throws PolicyViolationException 参数不是有效 JSON 时抛出
     */
    private JsonNode parseArguments(String arguments) {

        try {
            return objectMapper.readTree(arguments);
        } catch (Exception e) {
            // 原始参数可能包含不可信文本；拒绝信息只报告固定错误，不回显模型内容。
            throw new PolicyViolationException("Invalid tool arguments", e);
        }
    }

    /**
     * 严格解析提案参数；此方法只用于新增的副作用提案工具，不改变既有只读工具的历史解析行为。
     *
     * @param arguments 模型生成的 JSON 参数文本
     * @return 拒绝重复键、尾随 token 和非 JSON 对象后的参数树
     * @throws PolicyViolationException 参数不满足严格 JSON 语法时抛出
     */
    private static JsonNode parseScaleProposalArguments(String arguments) {
        try {
            JsonNode parsed = STRICT_TOOL_ARGUMENT_MAPPER.readTree(arguments);
            if (parsed == null || !parsed.isObject()) {
                throw new PolicyViolationException("Scale proposal arguments must be a JSON object");
            }
            return parsed;
        } catch (PolicyViolationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PolicyViolationException("Scale proposal arguments are not strict JSON", exception);
        }
    }

    /**
     * 对模型提出的扩容工具调用执行第一层服务端参数门禁。
     *
     * <p>这里拒绝多余字段，尤其是试图附加的 approvalRequired、approved 或 decision 字段；
     * 审批要求与决策身份由 Java 服务端产生，不能由模型输入控制。业务服务还会重复校验，
     * 以便绕过 AgentRunner 的其他内部调用也不能提交越界目标。</p>
     *
     * @param arguments 已解析的模型工具参数 JSON 对象
     * @throws PolicyViolationException 字段形状、白名单或参数范围无效时抛出
     */
    private static void checkScaleProposalArguments(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            throw new PolicyViolationException("Scale proposal arguments must be a JSON object");
        }
        Set<String> expectedFields = Set.of("topic", "replicas", "reason");
        Set<String> actualFields = arguments.properties().stream()
                .map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!actualFields.equals(expectedFields)) {
            throw new PolicyViolationException("Scale proposal has missing or unsupported fields");
        }

        JsonNode topicNode = arguments.get("topic");
        JsonNode replicasNode = arguments.get("replicas");
        JsonNode reasonNode = arguments.get("reason");
        if (topicNode == null || !topicNode.isString()
                || replicasNode == null || !replicasNode.isIntegralNumber() || !replicasNode.canConvertToInt()
                || reasonNode == null || !reasonNode.isString()) {
            throw new PolicyViolationException("Scale proposal arguments have invalid types");
        }

        String topic = topicNode.stringValue();
        if (!ALLOWED_TOPICS.contains(topic)) {
            throw new PolicyViolationException("Topic not allowed for scale proposal");
        }
        int replicas = replicasNode.intValue();
        if (replicas < 2 || replicas > 10) {
            throw new PolicyViolationException("Consumer replicas must be between 2 and 10");
        }
        String reason = reasonNode.stringValue();
        if (reason == null || reason.trim().isEmpty() || reason.length() > 300
                || reason.chars().anyMatch(Character::isISOControl)) {
            throw new PolicyViolationException("Scale proposal reason is invalid");
        }
    }

    /**
     * 由 AgentRunner 在单次请求范围内复用的策略计数状态；调用方不得跨请求共享。
     */
    public static final class RunState {
        /**
         * 上一次 Tool 调用签名，
         * 用于检测连续完全相同的 Tool 调用。
         */
        private String lastToolSignature;
        /**
         * 当前 Agent 请求累计申请的 Tool Call 数量。
         */
        private int totalToolCalls;
    }

    /**
     * 由服务端策略拒绝的工具调用异常；异常文本只包含固定安全诊断，不回显模型原始参数。
     */
    public static class PolicyViolationException extends RuntimeException {

        /**
         * 创建一个策略拒绝异常。
         *
         * @param message 固定且不包含敏感原始工具输入的拒绝说明
         */
        public PolicyViolationException(String message) {
            super(message);
        }

        /**
         * 创建包含底层 JSON 解析原因的策略拒绝异常。
         *
         * @param message 固定拒绝说明
         * @param cause   底层工具参数解析异常
         */
        public PolicyViolationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
