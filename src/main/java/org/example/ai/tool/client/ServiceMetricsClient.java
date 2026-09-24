package org.example.ai.tool.client;

import org.example.ai.tool.dto.ServiceStatus;

/**
 * 服务运行指标访问接口：抽象服务指标的数据来源。
 *
 * <p>设计原则：工具只依赖该接口，不关心指标来自 Mock、HTTP 还是未来的真实监控平台。
 * 这样可以在不修改工具定义的情况下切换数据来源。</p>
 *
 * <p>典型实现：
 * <ul>
 *   <li>Mock 实现：返回固定评测数据，用于开发和测试</li>
 *   <li>HTTP 实现：调用远程监控 API，用于生产环境</li>
 * </ul>
 * </p>
 */
public interface ServiceMetricsClient {

    /**
     * 查询指定服务当前运行状态：CPU、内存、线程池等指标。
     *
     * @param serviceName 服务名称，例如 "payment-service"
     * @return 服务运行指标，不可用时抛异常，禁止伪造正常值
     */
    ServiceStatus getServiceStatus(String serviceName);
}