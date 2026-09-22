package org.example.ai.harness;

import org.example.ai.tool.OpsTools;
import org.example.ai.tool.client.DependencyMetricsClient;
import org.example.ai.tool.client.impl.*;
import org.example.ai.tool.mock.MockScenario;
import org.example.ai.tool.mock.OpsMockDataProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 使用真实工具回调和 ToolCallingManager 验证模型下一轮收到的证据；不冒充真实 LLM Eval。 */
class DependencyEvidenceTest {
    private final OpsMockDataProvider data = new OpsMockDataProvider();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final TraceRecorder trace = new TraceRecorder();
    private static final String ARGS = "{\"serviceName\":\"payment-service\"}";
    private static final String URL = "http://observer/api/v1/services/payment-service/dependencies";

    @AfterEach
    void close() throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }

    private ToolCallback[] callbacks(DependencyMetricsClient client) {
        // 刻意移除日志证据，避免 DB/RPC 的数值实际上来自日志文本。
        return Arrays.stream(ToolCallbacks.from(new OpsTools(data, new MockServiceMetricsClient(data),
                (service, minutes) -> List.of(), client)))
                .map(callback -> new GuardedToolCallback(callback, executor, Duration.ofSeconds(2), 1, trace, () -> 1))
                .toArray(ToolCallback[]::new);
    }

    private AssistantMessage.ToolCall call(String name, String args) {
        return new AssistantMessage.ToolCall(name, "function", name, args);
    }

    @ParameterizedTest
    @EnumSource(value = MockScenario.class, names = {"E03_DATABASE_SLOW", "E04_RPC_TIMEOUT"})
    void combinesServiceKafkaAndDependencyWithoutLogs(MockScenario scenario) {
        data.useScenario(scenario);
        var calls = List.of(call("getServiceStatus", ARGS), call("getKafkaStatus", "{\"topic\":\"order-topic\"}"),
                call("getDependencyStatus", ARGS));
        var policy = new ExecutionPolicy();
        policy.check(calls, policy.newRunState());
        var options = OllamaChatOptions.builder().toolCallbacks(callbacks(new MockDependencyMetricsClient(data))).build();
        var response = new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(calls).build())));
        var result = ToolCallingManager.builder().build().executeToolCalls(new Prompt("排查消费变慢", options), response);
        var observations = result.conversationHistory().stream().filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast).flatMap(message -> message.getResponses().stream()).toList();
        assertEquals(3, observations.size());
        var mapper = JsonMapper.builder().build();
        var service = mapper.readTree(observations.get(0).responseData());
        var kafka = mapper.readTree(observations.get(1).responseData());
        var dependency = mapper.readTree(observations.get(2).responseData());
        assertTrue(service.path("cpuPercent").asDouble() < 60);
        assertTrue(service.path("threadPoolActive").asInt() > 100);
        assertTrue(kafka.path("consumeRate").asDouble() < kafka.path("produceRate").asDouble());
        if (scenario == MockScenario.E03_DATABASE_SLOW) {
            assertEquals(2500, dependency.path("database").path("p99Ms").asDouble());
            assertEquals(49, dependency.path("database").path("activeConnections").asInt());
            assertEquals(50, dependency.path("database").path("maxConnections").asInt());
        } else {
            assertEquals(3200, dependency.path("rpc").path("p99Ms").asDouble());
            assertEquals(0.35, dependency.path("rpc").path("timeoutRate").asDouble());
        }
        assertEquals(3, trace.snapshot().size());
        assertTrue(trace.snapshot().stream().allMatch(event -> event.status().startsWith("SUCCESS")));
    }

    @Test
    void rejectsUnauthorizedDependencyService() {
        var policy = new ExecutionPolicy();
        assertThrows(ExecutionPolicy.PolicyViolationException.class, () -> policy.check(
                List.of(call("getDependencyStatus", "{\"serviceName\":\"admin-service\"}")), policy.newRunState()));
    }

    @Test
    void unavailableDependencyIsAnErrorWithoutInventedMetrics() {
        RestClient.Builder builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        String result = dependencyCallback(new HttpDependencyMetricsClient(builder, "http://observer")).call(ARGS);
        var json = JsonMapper.builder().build().readTree(result);
        assertEquals("SERVER_ERROR", json.path("errorType").asString());
        assertFalse(json.path("dataAvailable").asBoolean());
        assertFalse(result.contains("p99Ms"));
        assertEquals(2, trace.snapshot().size());
        server.verify();
    }

    @Test
    void missingDependencyIsNotRetriedOrConvertedToNormal() {
        RestClient.Builder builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        String result = dependencyCallback(new HttpDependencyMetricsClient(builder, "http://observer")).call(ARGS);
        assertTrue(result.contains("INVALID_RESPONSE"));
        assertFalse(JsonMapper.builder().build().readTree(result).path("dataAvailable").asBoolean());
        assertEquals(1, trace.snapshot().size());
        server.verify();
    }

    private ToolCallback dependencyCallback(DependencyMetricsClient client) {
        return Arrays.stream(callbacks(client)).filter(callback -> callback.getToolDefinition().name()
                .equals("getDependencyStatus")).findFirst().orElseThrow();
    }
}
