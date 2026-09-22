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

/** 验证远端契约，特别是“缺失”和合法零值之间的区别。 */
class HttpDependencyMetricsClientTest {
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpDependencyMetricsClient client = new HttpDependencyMetricsClient(builder, "http://observer");
    private static final String VALID = """
            {"serviceName":"payment-service",
             "database":{"p99Ms":2500,"activeConnections":49,"maxConnections":50},
             "rpc":{"dependency":"payment-gateway","p99Ms":3200,"timeoutRate":0.35}}
            """;

    private void respond(String body) {
        server.expect(requestTo("http://observer/api/v1/services/payment-service/dependencies"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void readsIndependentMetrics() {
        respond(VALID);
        var result = client.getDependencyStatus("payment-service");
        assertEquals(2500, result.database().p99Ms());
        assertEquals(49, result.database().activeConnections());
        assertEquals(0.35, result.rpc().timeoutRate());
        server.verify();
    }

    @Test
    void acceptsRealZeroValues() {
        respond(VALID.replace("2500", "0").replace("49", "0").replace("3200", "0").replace("0.35", "0"));
        assertEquals(0, client.getDependencyStatus("payment-service").rpc().timeoutRate());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "{\"serviceName\":\"payment-service\",\"database\":{}}"})
    void rejectsMissingComponents(String body) {
        respond(body);
        assertThrows(InvalidToolResponseException.class, () -> client.getDependencyStatus("payment-service"));
        server.verify();
    }

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
