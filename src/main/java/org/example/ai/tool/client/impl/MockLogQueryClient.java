package org.example.ai.tool.client.impl;

import org.example.ai.tool.client.LogQueryClient;
import org.example.ai.tool.dto.ErrorLog;
import org.example.ai.tool.mock.OpsMockDataProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用于 Day6 等固定 Eval 的日志 Mock Adapter。
 */
@Component
@ConditionalOnProperty(
        prefix = "app.ops",
        name = "data-source",
        havingValue = "MOCK"
)
public class MockLogQueryClient implements LogQueryClient {

    private final OpsMockDataProvider dataProvider;

    public MockLogQueryClient(OpsMockDataProvider dataProvider) {
        this.dataProvider = dataProvider;
    }

    @Override
    public List<ErrorLog> queryErrorLogs(String serviceName, int minutes) {
        return dataProvider.queryErrorLogs(serviceName, minutes);
    }
}