package org.example.ai.tool.client;

import org.example.ai.tool.dto.ServiceStatus;

/**
 * 服务运行指标访问接口。
 *
 * <p>
 * Tool 只依赖该抽象，不关心指标来自 Mock、HTTP
 * 还是未来的真实监控平台。
 * </p>
 */
public interface ServiceMetricsClient {

    /**
     * 查询指定服务当前运行状态。
     *
     * @param serviceName 服务名称
     * @return 服务运行指标
     */
    ServiceStatus getServiceStatus(String serviceName);
}