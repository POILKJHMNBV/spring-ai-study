package org.example.ai.tool.dto;

public record ServiceStatus(
        String serviceName,
        double cpuPercent,
        double memoryPercent,
        int threadPoolActive,
        int threadPoolMax,
        boolean healthy
) {
}
