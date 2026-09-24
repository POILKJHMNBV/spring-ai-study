package org.example.ai.harness;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

/**
 * 工具调用安全策略：在工具执行前校验调用合法性，防止越权、滥用或死循环。
 *
 * <p>校验规则：
 * <ul>
 *   <li>工具总量限制：单次 Agent 请求最多 8 次工具调用，防止资源滥用</li>
 *   <li>工具白名单：只允许调用预定义的 4 个工具</li>
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
            "queryErrorLogs"
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    public RunState newRunState() {
        return new RunState();
    }

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

            JsonNode arguments = parseArguments(call.arguments());

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

    private JsonNode parseArguments(String arguments) {

        try {
            return objectMapper.readTree(arguments);
        }
        catch (Exception e) {
            throw new PolicyViolationException("Invalid tool arguments: " + arguments, e);
        }
    }

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

    public static class PolicyViolationException extends RuntimeException {

        public PolicyViolationException(String message) {
            super(message);
        }

        public PolicyViolationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
