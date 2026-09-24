package org.example.ai.tool.dto;

/**
 * 服务运行状态快照：包含 CPU、内存、线程池和健康状态。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code serviceName} — 服务名称，例如 "payment-service"</li>
 *   <li>{@code cpuPercent} — CPU 使用率，范围 0～100</li>
 *   <li>{@code memoryPercent} — 内存使用率，范围 0～100</li>
 *   <li>{@code threadPoolActive} — 线程池活跃线程数</li>
 *   <li>{@code threadPoolMax} — 线程池最大线程数</li>
 *   <li>{@code healthy} — 服务是否健康</li>
 * </ul>
 * </p>
 */
public record ServiceStatus(
        String serviceName,
        double cpuPercent,
        double memoryPercent,
        int threadPoolActive,
        int threadPoolMax,
        boolean healthy
) {
}
