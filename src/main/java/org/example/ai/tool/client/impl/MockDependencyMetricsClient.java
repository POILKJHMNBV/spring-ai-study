package org.example.ai.tool.client.impl;

import org.example.ai.tool.client.DependencyMetricsClient;
import org.example.ai.tool.dto.DependencyStatus;
import org.example.ai.tool.mock.OpsMockDataProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 固定 Eval 的依赖指标适配器，与服务、Kafka、日志共享同一场景。 */
@Component
@ConditionalOnProperty(prefix = "app.ops", name = "data-source", havingValue = "MOCK")
public class MockDependencyMetricsClient implements DependencyMetricsClient {
    private final OpsMockDataProvider dataProvider;

    public MockDependencyMetricsClient(OpsMockDataProvider dataProvider) {
        this.dataProvider = dataProvider;
    }

    @Override
    public DependencyStatus getDependencyStatus(String serviceName) {
        return dataProvider.getDependencyStatus(serviceName);
    }
}
