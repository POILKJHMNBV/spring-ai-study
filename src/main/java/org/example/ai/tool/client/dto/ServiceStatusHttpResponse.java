package org.example.ai.tool.client.dto;

/**
 * service-observer 返回的 HTTP DTO。
 *
 * <p>
 * 使用包装类型是为了区分：
 * 1. 字段真实值为 0；
 * 2. HTTP JSON 根本没有返回该字段。
 * </p>
 */
public record ServiceStatusHttpResponse(
        String serviceName,
        Double cpuPercent,
        Double memoryPercent,
        Integer threadPoolActive,
        Integer threadPoolMax,
        Boolean healthy
) {
}