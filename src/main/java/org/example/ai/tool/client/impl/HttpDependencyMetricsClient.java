package org.example.ai.tool.client.impl;

import org.example.ai.tool.client.DependencyMetricsClient;
import org.example.ai.tool.client.InvalidToolResponseException;
import org.example.ai.tool.dto.DependencyStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** HTTP 依赖指标适配器；重试、超时和失败分类统一交给 Day9 Harness。 */
@Component
@ConditionalOnProperty(prefix = "app.ops", name = "data-source", havingValue = "HTTP", matchIfMissing = true)
public class HttpDependencyMetricsClient implements DependencyMetricsClient {
    private final RestClient restClient;

    public HttpDependencyMetricsClient(RestClient.Builder builder,
            @Value("${app.ops.base-url:http://127.0.0.1:18081}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public DependencyStatus getDependencyStatus(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName must not be blank");
        }
        DependencyStatus response = restClient.get()
                .uri("/api/v1/services/{serviceName}/dependencies", serviceName)
                .accept(MediaType.APPLICATION_JSON).retrieve().body(DependencyStatus.class);
        validate(serviceName, response);
        return response;
    }

    /** 严格校验完整快照：任一组件缺失均视为不可用，不将 null 转成零。 */
    private static void validate(String serviceName, DependencyStatus response) {
        if (response == null || !serviceName.equals(response.serviceName())
                || response.database() == null || response.rpc() == null) {
            throw new InvalidToolResponseException("依赖指标缺失或响应服务不匹配");
        }
        DependencyStatus.Database db = response.database();
        DependencyStatus.Rpc rpc = response.rpc();
        if (illegalNum(db.p99Ms()) || db.activeConnections() == null || db.maxConnections() == null
                || db.activeConnections() < 0 || db.maxConnections() <= 0
                || db.activeConnections() > db.maxConnections()
                || rpc.dependency() == null || rpc.dependency().isBlank()
                || illegalNum(rpc.p99Ms()) || illegalNum(rpc.timeoutRate()) || rpc.timeoutRate() > 1) {
            throw new InvalidToolResponseException("依赖指标字段缺失或数值超出有效范围");
        }
    }


    private static boolean illegalNum(Double value) {
        return value == null || value < 0 || !Double.isFinite(value);
    }

}
