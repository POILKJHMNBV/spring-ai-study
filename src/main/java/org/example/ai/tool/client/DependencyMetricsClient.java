package org.example.ai.tool.client;

import org.example.ai.tool.dto.DependencyStatus;

/** 统一依赖指标入口，将工具定义与 HTTP/固定评测数据源解耦。 */
public interface DependencyMetricsClient {
    /** 查询服务对应的 DB/RPC 指标；不可用时抛异常，禁止伪造正常值。 */
    DependencyStatus getDependencyStatus(String serviceName);
}
