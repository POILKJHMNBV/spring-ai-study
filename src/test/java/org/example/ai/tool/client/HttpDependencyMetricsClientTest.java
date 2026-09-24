package org.example.ai.tool.client;

import org.example.ai.tool.client.impl.HttpDependencyMetricsClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Day10 HTTP Adapter 契约测试：验证 {@link HttpDependencyMetricsClient} 对依赖指标响应的处理。
 *
 * <p>测试场景：
 * <ul>
 *   <li>{@link #readsIndependentMetrics} — 正常场景：读取完整的 DB/RPC 指标</li>
 *   <li>{@link #acceptsRealZeroValues} — 合法零值：指标为 0 时应正常接受，不视为缺失</li>
 *   <li>{@link #rejectsMissingComponents} — 缺少必要组件：空响应、空 database 等</li>
 *   <li>{@link #rejectsInvalidFields} — 无效字段：服务名不匹配、负值、超出范围等</li>
 * </ul>
 * </p>
 *
 * <p>核心验证：区分“缺失”和合法零值。缺失必须抛出 {@link InvalidToolResponseException}，
 * 零值必须正常接受，防止模型将缺失数据误认为零值、健康或无异常。</p>
 */
class HttpDependencyMetricsClientTest {
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpDependencyMetricsClient client = new HttpDependencyMetricsClient(builder, "http://observer");
    
    /** 有效的依赖指标 JSON 响应。 */
    private static final String VALID = """
            {"serviceName":"payment-service",
             "database":{"p99Ms":2500,"activeConnections":49,"maxConnections":50},
             "rpc":{"dependency":"payment-gateway","p99Ms":3200,"timeoutRate":0.35}}
            """;

    /** 辅助方法：设置 Mock 服务器响应。 */
    private void respond(String body) {
        server.expect(requestTo("http://observer/api/v1/services/payment-service/dependencies"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    /**
     * 测试正常场景：读取完整的 DB/RPC 指标。
     * 验证 Client 能正确解析所有字段。
     */
    @Test
    void readsIndependentMetrics() {
        respond(VALID);
        var result = client.getDependencyStatus("payment-service");
        assertEquals(2500, result.database().p99Ms());
        assertEquals(49, result.database().activeConnections());
        assertEquals(0.35, result.rpc().timeoutRate());
        server.verify();
    }

    /**
     * 测试合法零值：指标为 0 时应正常接受，不视为缺失。
     * 验证 Client 能区分“缺失”和“零值”。
     */
    @Test
    void acceptsRealZeroValues() {
        respond(VALID.replace("2500", "0").replace("49", "0").replace("3200", "0").replace("0.35", "0"));
        assertEquals(0, client.getDependencyStatus("payment-service").rpc().timeoutRate());
        server.verify();
    }

    /**
     * 测试缺失组件：空响应、空 JSON、空 database 等应抛出 {@link InvalidToolResponseException}。
     * 参数化测试覆盖多种缺失场景。
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "{\"serviceName\":\"payment-service\",\"database\":{}}"})
    void rejectsMissingComponents(String body) {
        respond(body);
        assertThrows(InvalidToolResponseException.class, () -> client.getDependencyStatus("payment-service"));
        server.verify();
    }

    /**
     * 测试无效字段：服务名不匹配、负值、超出范围等应抛出 {@link InvalidToolResponseException}。
     * 参数化测试覆盖多种无效字段场景。
     */
    @ParameterizedTest
    @ValueSource(strings = {"service", "missing", "negative", "capacity", "zeroCapacity", "rate", "dependency", "rpcMissing", "latency"})
    void rejectsInvalidFields(String variant) {
        String body = switch (variant) {
            case "service" -> VALID.replace("payment-service", "other-service");
            case "missing" -> VALID.replace("\"activeConnections\":49,", "");
            case "negative" -> VALID.replace("2500", "-1");
            case "capacity" -> VALID.replace("49", "51");
            case "zeroCapacity" -> VALID.replace("50}", "0}");
            case "rate" -> VALID.replace("0.35", "1.01");
            case "dependency" -> VALID.replace("payment-gateway", "");
            case "rpcMissing" -> VALID.replace("\"timeoutRate\":0.35", "\"unused\":0.35");
            default -> VALID.replace("3200", "-1");
        };
        respond(body);
        assertThrows(InvalidToolResponseException.class, () -> client.getDependencyStatus("payment-service"));
        server.verify();
    }
}
