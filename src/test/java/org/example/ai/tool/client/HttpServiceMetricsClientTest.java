package org.example.ai.tool.client;

import org.example.ai.tool.client.impl.HttpServiceMetricsClient;
import org.example.ai.tool.dto.ServiceStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Day8 HTTP Adapter 契约测试：验证 {@link HttpServiceMetricsClient} 对 HTTP 响应的处理。
 *
 * <p>测试场景：
 * <ul>
 *   <li>{@link #shouldReadServiceStatusWhenHttp200} — 正常 HTTP 200，完整 JSON 响应</li>
 *   <li>{@link #shouldRejectIncompleteResponse} — 响应缺少必须字段，应抛出异常</li>
 *   <li>{@link #shouldRejectWrongContentType} — Content-Type 不是 application/json，应抛出异常</li>
 * </ul>
 * </p>
 *
 * <p>使用 {@link MockRestServiceServer} 模拟 HTTP 服务器，不需要启动真实的 Service Observer。</p>
 */
class HttpServiceMetricsClientTest {

    /**
     * 测试正常场景：HTTP 200，完整 JSON 响应。
     * 验证 Client 能正确解析所有字段。
     */
    @Test
    void shouldReadServiceStatusWhenHttp200() {

        RestClient.Builder builder =
                RestClient.builder();

        MockRestServiceServer server =
                MockRestServiceServer
                        .bindTo(builder)
                        .build();

        HttpServiceMetricsClient client =
                new HttpServiceMetricsClient(
                        builder,
                        "http://service-observer"
                );

        server.expect(
                        requestTo(
                                "http://service-observer"
                                        + "/api/v1/services/"
                                        + "payment-service/status"
                        )
                )
                .andExpect(method(HttpMethod.GET))
                .andRespond(
                        withSuccess(
                                """
                                {
                                  "serviceName": "payment-service",
                                  "cpuPercent": 55.0,
                                  "memoryPercent": 68.0,
                                  "threadPoolActive": 200,
                                  "threadPoolMax": 200,
                                  "healthy": true
                                }
                                """,
                                MediaType.APPLICATION_JSON
                        )
                );

        ServiceStatus result =
                client.getServiceStatus(
                        "payment-service"
                );

        assertEquals(
                "payment-service",
                result.serviceName()
        );
        assertEquals(
                55.0,
                result.cpuPercent()
        );
        assertEquals(
                200,
                result.threadPoolActive()
        );

        server.verify();
    }

    /**
     * 测试异常场景：响应缺少必须字段 memoryPercent。
     * 验证 Client 能识别不完整响应并抛出 {@link IllegalStateException}。
     */
    @Test
    void shouldRejectIncompleteResponse() {

        RestClient.Builder builder =
                RestClient.builder();

        MockRestServiceServer server =
                MockRestServiceServer
                        .bindTo(builder)
                        .build();

        HttpServiceMetricsClient client =
                new HttpServiceMetricsClient(
                        builder,
                        "http://service-observer"
                );

        server.expect(
                        requestTo(
                                "http://service-observer"
                                        + "/api/v1/services/"
                                        + "payment-service/status"
                        )
                )
                .andRespond(
                        withSuccess(
                                """
                                {
                                  "serviceName": "payment-service",
                                  "cpuPercent": 55.0,
                                  "threadPoolActive": 200,
                                  "threadPoolMax": 200,
                                  "healthy": true
                                }
                                """,
                                MediaType.APPLICATION_JSON
                        )
                );

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                client.getServiceStatus(
                                        "payment-service"
                                )
                );

        assertTrue(
                exception.getMessage()
                        .contains("memoryPercent")
        );

        server.verify();
    }

    /**
     * 测试异常场景：Content-Type 不是 application/json。
     * 验证 Client 能正确校验 Content-Type 并抛出 {@link RestClientException}。
     */
    @Test
    void shouldRejectWrongContentType() {

        RestClient.Builder builder =
                RestClient.builder();

        MockRestServiceServer server =
                MockRestServiceServer
                        .bindTo(builder)
                        .build();

        HttpServiceMetricsClient client =
                new HttpServiceMetricsClient(
                        builder,
                        "http://service-observer"
                );

        server.expect(
                        requestTo(
                                "http://service-observer"
                                        + "/api/v1/services/"
                                        + "payment-service/status"
                        )
                )
                .andRespond(
                        withSuccess(
                                "not-json",
                                MediaType.TEXT_PLAIN
                        )
                );

        assertThrows(
                RestClientException.class,
                () ->
                        client.getServiceStatus(
                                "payment-service"
                        )
        );

        server.verify();
    }
}