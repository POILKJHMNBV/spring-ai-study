package org.example.ai.tool.client;

import org.example.ai.tool.dto.ErrorLog;

import java.util.List;

/**
 * 服务日志查询接口。
 */
public interface LogQueryClient {

    /**
     * 查询指定服务最近一段时间的错误日志。
     *
     * @param serviceName 服务名称
     * @param minutes     向前查询分钟数
     * @return 错误日志摘要
     */
    List<ErrorLog> queryErrorLogs(String serviceName, int minutes);
}