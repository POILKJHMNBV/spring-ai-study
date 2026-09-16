package org.example.ai.harness;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

@Component
public class ExecutionPolicy {
    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "getKafkaStatus",
            "getServiceStatus",
            "queryErrorLogs"
    );

    private static final Set<String> ALLOWED_SERVICES = Set.of(
            "payment-service"
    );

    private static final Set<String> ALLOWED_TOPICS = Set.of(
            "order-topic"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    public RunState newRunState() {
        return new RunState();
    }

    public void check(
            List<AssistantMessage.ToolCall> toolCalls,
            RunState state
    ) {

        for (AssistantMessage.ToolCall call : toolCalls) {

            String toolName = call.name();

            // 1. Tool 白名单
            if (!ALLOWED_TOOLS.contains(toolName)) {
                throw new PolicyViolationException(
                        "Tool not allowed: " + toolName
                );
            }

            JsonNode arguments = parseArguments(call.arguments());

            // 2. 参数权限
            switch (toolName) {

                case "getKafkaStatus" -> {

                    String topic = arguments.path("topic").asString();

                    if (!ALLOWED_TOPICS.contains(topic)) {
                        throw new PolicyViolationException(
                                "Topic not allowed: " + topic
                        );
                    }
                }

                case "getServiceStatus",
                        "queryErrorLogs" -> {

                    String serviceName =
                            arguments.path("serviceName").asString();

                    if (!ALLOWED_SERVICES.contains(serviceName)) {
                        throw new PolicyViolationException(
                                "Service not allowed: " + serviceName
                        );
                    }
                }

                default -> throw new PolicyViolationException(
                        "Unexpected tool: " + toolName
                );
            }

            // 3. 连续重复调用检测
            String signature = toolName + ":" + arguments;

            if (signature.equals(state.lastToolSignature)) {
                throw new PolicyViolationException(
                        "Repeated tool call detected: " + signature
                );
            }

            state.lastToolSignature = signature;
        }
    }

    private JsonNode parseArguments(String arguments) {

        try {
            return objectMapper.readTree(arguments);
        }
        catch (Exception e) {
            throw new PolicyViolationException(
                    "Invalid tool arguments: " + arguments,
                    e
            );
        }
    }

    public static final class RunState {

        private String lastToolSignature;
    }

    public static class PolicyViolationException
            extends RuntimeException {

        public PolicyViolationException(String message) {
            super(message);
        }

        public PolicyViolationException(
                String message,
                Throwable cause
        ) {
            super(message, cause);
        }
    }
}
