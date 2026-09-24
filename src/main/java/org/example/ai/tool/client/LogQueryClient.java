package org.example.ai.tool.client;

import org.example.ai.tool.dto.ErrorLog;

import java.util.List;

/**
 * 服务日志查询接口：抽象错误日志的数据来源。
 *
 * <p>设计原则：工具只依赖该接口，不关心日志来自 Mock 还是真实的日志平台。
 * 这样可以在不修改工具定义的情况下切换数据来源。</p>
 */
public interface LogQueryClient {

    /**
     * 查询指定服务最近一段时间的错误日志摘要。
     *
     * @param serviceName 服务名称，例如 "payment-service"
     * @param minutes     向前查询分钟数，例如 10
     * @return 错误日志摘要列表，不可用时抛异常，禁止伪造正常值
     */
    List<ErrorLog> queryErrorLogs(String serviceName, int minutes);
}