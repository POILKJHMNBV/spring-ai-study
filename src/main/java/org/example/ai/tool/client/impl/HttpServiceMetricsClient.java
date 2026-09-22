package org.example.ai.tool.client.impl;

import org.example.ai.tool.client.ServiceMetricsClient;
import org.example.ai.tool.client.dto.ServiceStatusHttpResponse;
import org.example.ai.tool.dto.ServiceStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.example.ai.tool.client.InvalidToolResponseException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 HTTP 的服务运行指标 Client。
 *
 * <p>
 * HTTP URL、JSON 解析以及远端契约校验全部封装在 Client 内，
 * OpsTools 不承担这些职责。
 * </p>
 */
@Component
@ConditionalOnProperty(
        prefix = "app.ops",
        name = "data-source",
        havingValue = "HTTP",
        matchIfMissing = true
)
public class HttpServiceMetricsClient implements ServiceMetricsClient {
    private final RestClient restClient;
    public HttpServiceMetricsClient(RestClient.Builder restClientBuilder,
            @Value("${app.ops.base-url:http://127.0.0.1:18081}") String baseUrl
    ) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    /**
     * 从远程 service-observer 获取实时服务指标。
     */
    @Override
    public ServiceStatus getServiceStatus(String serviceName) {

        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName must not be blank");
        }

        ServiceStatusHttpResponse response =
                restClient
                        .get()
                        .uri("/api/v1/services/{serviceName}/status", serviceName)
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(ServiceStatusHttpResponse.class);

        return validateAndConvert(serviceName, response);
    }

    /**
     * 对远端 HTTP Contract 做最低限度校验。
     *
     * <p>
     * 明确区分“真实的 0”与“远端字段缺失”。
     * 契约异常由 Harness 分类为 INVALID_RESPONSE，禁止重试。
     * </p>
     */
    private ServiceStatus validateAndConvert(String requestedServiceName, ServiceStatusHttpResponse response) {

        if (response == null) {
            throw new InvalidToolResponseException("服务指标 HTTP 响应为空");
        }

        List<String> missingFields = getMissingFields(response);

        if (!missingFields.isEmpty()) {
            throw new InvalidToolResponseException("服务指标 HTTP 数据不完整，缺失字段：" + missingFields);
        }

        /*
         * 防止请求 payment-service，
         * 远端却错误地返回另一个服务的数据。
         */
        if (!requestedServiceName.equals(response.serviceName())) {
            throw new InvalidToolResponseException(
                    "服务指标 HTTP 响应与请求服务不一致，requested="
                            + requestedServiceName
                            + ", actual="
                            + response.serviceName()
            );
        }

        return new ServiceStatus(
                response.serviceName(),
                response.cpuPercent(),
                response.memoryPercent(),
                response.threadPoolActive(),
                response.threadPoolMax(),
                response.healthy()
        );
    }

    /**
     * 获取缺失的字段列表。
     * @param response HTTP 响应
     * @return 缺失的字段列表
     */
    private static List<String> getMissingFields(ServiceStatusHttpResponse response) {
        List<String> missingFields = new ArrayList<>();

        if (response.serviceName() == null
                || response.serviceName().isBlank()) {
            missingFields.add("serviceName");
        }

        if (response.cpuPercent() == null) {
            missingFields.add("cpuPercent");
        }

        if (response.memoryPercent() == null) {
            missingFields.add("memoryPercent");
        }

        if (response.threadPoolActive() == null) {
            missingFields.add("threadPoolActive");
        }

        if (response.threadPoolMax() == null) {
            missingFields.add("threadPoolMax");
        }

        if (response.healthy() == null) {
            missingFields.add("healthy");
        }
        return missingFields;
    }
}