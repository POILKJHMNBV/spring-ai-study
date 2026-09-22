package org.example.ai.harness;

import org.example.ai.tool.client.impl.HttpServiceMetricsClient;
import org.example.ai.tool.client.impl.HttpLogQueryClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 通过真实 Spring AI 反射回调与 HTTP Adapter 验证重试、异常解包和失败 Observation。 */
class ToolRecoveryTest {
    private static final String URL = "http://observer/api/v1/services/payment-service/status";
    private static final String VALID = """
            {"serviceName":"payment-service","cpuPercent":55.0,"memoryPercent":68.0,
            "threadPoolActive":200,"threadPoolMax":200,"healthy":true}
            """;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final TraceRecorder trace = new TraceRecorder();
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    @AfterEach
    void close() throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }

    private GuardedToolCallback guard(Object tool, Duration timeout) {
        ToolCallback callback = ToolCallbacks.from(tool)[0];
        return new GuardedToolCallback(callback, executor, timeout, 1, trace, () -> 1);
    }

    private String invokeHttp() {
        return guard(new MetricsTool(new HttpServiceMetricsClient(builder, "http://observer")),
                Duration.ofSeconds(2)).call("{}");
    }

    @Test
    void serviceUnavailableThenSuccess() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(URL)).andRespond(withSuccess(VALID, MediaType.APPLICATION_JSON));
        assertTrue(invokeHttp().contains("55.0"));
        assertEquals("SERVER_ERROR_RETRY, attempt=1", trace.snapshot().get(0).status());
        assertEquals("SUCCESS, attempt=2", trace.snapshot().get(1).status());
        server.verify();
    }

    @Test
    void toolCallingManagerPlacesFailureInModelContext() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{broken", MediaType.APPLICATION_JSON));
        GuardedToolCallback callback = guard(new MetricsTool(new HttpServiceMetricsClient(builder,
                "http://observer")), Duration.ofSeconds(2));
        var options = OllamaChatOptions.builder().toolCallbacks(callback).build();
        var response = new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("").toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function",
                        "metrics", "{}"))).build())));
        var result = ToolCallingManager.builder().build().executeToolCalls(new Prompt("排查服务", options), response);
        // 验证真正传给下一轮模型的 ToolResponseMessage，而非只检查返回字符串。
        var observation = result.conversationHistory().stream()
                .filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                .findFirst().orElseThrow().getResponses().get(0);
        assertFailure(observation.responseData(), "INVALID_RESPONSE");
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"400,UNKNOWN,1", "401,AUTH_FAILURE,1", "403,AUTH_FAILURE,1",
            "429,RATE_LIMITED,1", "500,SERVER_ERROR,2", "503,SERVER_ERROR,2", "501,SERVER_ERROR,1"})
    void statusMatrix(int status, String type, int attempts) {
        for (int i = 0; i < attempts; i++) {
            server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.valueOf(status)));
        }
        assertFailure(invokeHttp(), type);
        assertEquals(attempts, trace.snapshot().size());
        server.verify();
    }

    @Test
    void rateLimitWithRetryAfterRecovers() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "1"));
        server.expect(requestTo(URL)).andRespond(withSuccess(VALID, MediaType.APPLICATION_JSON));
        long start = System.nanoTime();
        assertTrue(invokeHttp().contains("55.0"));
        assertTrue(System.nanoTime() - start >= TimeUnit.SECONDS.toNanos(1));
        assertEquals("RATE_LIMITED_RETRY, attempt=1", trace.snapshot().get(0).status());
        server.verify();
    }

    @Test
    void invalidOrMissingDataIsNotAnEmptySuccess() {
        for (String body : new String[]{"{broken-json", "{}", ""}) {
            server.reset();
            server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertFailure(invokeHttp(), "INVALID_RESPONSE");
            server.verify();
        }
    }

    @Test
    void wrongContentTypeIsNotRetried() {
        server.expect(requestTo(URL)).andRespond(withSuccess("text", MediaType.TEXT_PLAIN));
        assertFailure(invokeHttp(), "INVALID_RESPONSE");
        server.verify();
    }

    @Test
    void excessiveRetryAfterStopsWithoutEarlyRequest() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "3600"));
        assertFailure(invokeHttp(), "RATE_LIMITED");
        assertEquals(1, trace.snapshot().size());
        server.verify();
    }

    @Test
    void unknownAndPolicyFailuresAreNotRetried() {
        for (boolean policy : new boolean[]{false, true}) {
            BrokenTool tool = new BrokenTool(policy);
            assertFailure(guard(tool, Duration.ofSeconds(1)).call("{}"),
                    policy ? "POLICY_REJECTED" : "UNKNOWN");
            assertEquals(1, tool.calls.get());
        }
    }

    @Test
    void interruptedCallerStopsAndPreservesInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class,
                    () -> guard(new SlowTool(), Duration.ofSeconds(1)).call("{}"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals("INTERRUPTED, attempt=1", trace.snapshot().get(0).status());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void connectionAndReadTimeoutAreRetriedOnce() {
        for (boolean timeout : new boolean[]{false, true}) {
            server.reset();
            for (int i = 0; i < 2; i++) {
                server.expect(requestTo(URL)).andRespond(withException(timeout
                        ? new SocketTimeoutException("secret") : new ConnectException("secret")));
            }
            assertFailure(invokeHttp(), timeout ? "TIMEOUT" : "CONNECTION_FAILURE");
            server.verify();
        }
    }

    @Test
    void harnessTimeoutIsTracedAndCancelled() {
        SlowTool tool = new SlowTool();
        assertFailure(guard(tool, Duration.ofMillis(100)).call("{}"), "TIMEOUT");
        assertEquals(2, tool.calls.get());
        assertEquals("TIMEOUT_RETRY, attempt=1", trace.snapshot().get(0).status());
        assertEquals("TIMEOUT, attempt=2", trace.snapshot().get(1).status());
    }

    @Test
    void invalidLogResponseUsesSameTaxonomy() {
        server.expect(requestTo("http://observer/api/v1/services/payment-service/logs?minutes=5"))
                .andRespond(withSuccess("[{}]", MediaType.APPLICATION_JSON));
        assertFailure(guard(new LogsTool(new HttpLogQueryClient(builder, "http://observer")),
                Duration.ofSeconds(2)).call("{}"), "INVALID_RESPONSE");
        server.verify();
    }

    private void assertFailure(String result, String type) {
        var json = JsonMapper.builder().build().readTree(result);
        assertEquals("ERROR", json.path("status").asString());
        assertEquals(type, json.path("errorType").asString());
        assertFalse(json.path("dataAvailable").asBoolean());
        assertFalse(result.contains("secret"));
        assertFalse(result.contains("cpuPercent"));
    }

    public record MetricsTool(HttpServiceMetricsClient client) {
        @Tool(description = "测试服务指标")
        public Object metrics() { return client.getServiceStatus("payment-service"); }
    }

    public record LogsTool(HttpLogQueryClient client) {
        @Tool(description = "测试日志")
        public Object logs() { return client.queryErrorLogs("payment-service", 5); }
    }

    public static class SlowTool {
        final AtomicInteger calls = new AtomicInteger();
        @Tool(description = "可取消的慢工具")
        public String slow() throws InterruptedException {
            calls.incrementAndGet();
            Thread.sleep(10_000);
            return "unexpected";
        }
    }

    public static class BrokenTool {
        final AtomicInteger calls = new AtomicInteger();
        private final boolean policy;
        BrokenTool(boolean policy) { this.policy = policy; }
        @Tool(description = "不可恢复错误")
        public String broken() {
            calls.incrementAndGet();
            if (policy) throw new ExecutionPolicy.PolicyViolationException("secret");
            throw new IllegalStateException("secret");
        }
    }
}
