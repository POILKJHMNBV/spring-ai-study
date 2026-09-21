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
 * Day8 HTTP Adapter Contract 测试。
 */
class HttpServiceMetricsClientTest {

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