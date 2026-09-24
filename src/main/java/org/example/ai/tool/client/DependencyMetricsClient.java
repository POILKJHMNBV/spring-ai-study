package org.example.ai.tool.client;

import org.example.ai.tool.dto.DependencyStatus;

/**
 * 依赖指标访问接口：抽象 DB/RPC 指标的数据来源。
 *
 * <p>设计原则：将工具定义与数据来源解耦，工具只依赖该接口，不关心指标来自 Mock 还是 HTTP。
 * 这样可以在不修改工具定义的情况下切换数据来源。</p>
 */
public interface DependencyMetricsClient {
    /**
     * 查询服务对应的 DB/RPC 指标：数据库 P99、连接池占用、下游 RPC P99、超时率。
     *
     * @param serviceName 服务名称，例如 "payment-service"
     * @return 依赖指标，不可用时抛异常，禁止伪造正常值
     */
    DependencyStatus getDependencyStatus(String serviceName);
}
