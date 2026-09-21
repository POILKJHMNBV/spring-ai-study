package org.example.ai.tool.client.impl;

import org.example.ai.tool.client.LogQueryClient;
import org.example.ai.tool.client.dto.ErrorLogHttpResponse;
import org.example.ai.tool.dto.ErrorLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.List;

/**
 * 基于 HTTP 的日志查询 Client。
 */
@Component
@ConditionalOnProperty(
        prefix = "app.ops",
        name = "data-source",
        havingValue = "HTTP",
        matchIfMissing = true
)
public class HttpLogQueryClient implements LogQueryClient {

    private final RestClient restClient;

    public HttpLogQueryClient(RestClient.Builder restClientBuilder,
            @Value("${app.ops.base-url:http://127.0.0.1:18081}") String baseUrl
    ) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    @Override
    public List<ErrorLog> queryErrorLogs(String serviceName, int minutes) {

        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName must not be blank");
        }

        if (minutes <= 0) {
            throw new IllegalArgumentException("minutes must be greater than 0");
        }

        ErrorLogHttpResponse[] response =
                restClient
                        .get()
                        .uri(
                                "/api/v1/services/{serviceName}/logs"
                                        + "?minutes={minutes}",
                                serviceName,
                                minutes
                        )
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(ErrorLogHttpResponse[].class);

        if (response == null) {
            throw new IllegalStateException("日志 HTTP 响应为空");
        }

        return Arrays.stream(response)
                .map(this::convert)
                .toList();
    }

    /**
     * 防止远端返回结构不完整的日志对象。
     */
    private ErrorLog convert(ErrorLogHttpResponse response) {

        if (response == null
                || response.level() == null
                || response.level().isBlank()
                || response.message() == null
                || response.message().isBlank()) {

            throw new IllegalStateException("日志 HTTP 数据不完整");
        }

        return new ErrorLog(
                response.level(),
                response.message()
        );
    }
}