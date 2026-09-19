package org.example.ai.harness;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

@Component
public class ExecutionPolicy {
    /**
     * 单次 Agent 请求允许的最大 Tool Call 数量。
     *
     * <p>
     * MAX_STEPS 限制模型循环次数；
     * MAX_TOOL_CALLS_PER_RUN 限制一次响应同时申请多个 Tool 时的总量。
     * 两个限制解决的问题不同。
     * </p>
     */
    private static final int MAX_TOOL_CALLS_PER_RUN = 8;

    /**
     * 允许调用的tool列表
     */
    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "getKafkaStatus",
            "getServiceStatus",
            "queryErrorLogs"
    );

    /**
     * 允许调用的service列表
     */
    private static final Set<String> ALLOWED_SERVICES = Set.of(
            "payment-service"
    );

    /**
     * 允许查询的topic列表
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
            throw new IllegalStateException("单次 Agent 请求的 Tool 调用数量超过限制: " + MAX_TOOL_CALLS_PER_RUN);
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

                case "getServiceStatus", "queryErrorLogs" -> {

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
