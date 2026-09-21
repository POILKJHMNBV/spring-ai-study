package org.example.ai.tool.client.impl;

import org.example.ai.tool.client.ServiceMetricsClient;
import org.example.ai.tool.dto.ServiceStatus;
import org.example.ai.tool.mock.OpsMockDataProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 仅用于固定回归评测的 Mock Adapter。
 */
@Component
@ConditionalOnProperty(
        prefix = "app.ops",
        name = "data-source",
        havingValue = "MOCK"
)
public class MockServiceMetricsClient implements ServiceMetricsClient {

    private final OpsMockDataProvider dataProvider;

    public MockServiceMetricsClient(OpsMockDataProvider dataProvider) {
        this.dataProvider = dataProvider;
    }

    @Override
    public ServiceStatus getServiceStatus(String serviceName) {
        return dataProvider.getServiceStatus(serviceName);
    }
}