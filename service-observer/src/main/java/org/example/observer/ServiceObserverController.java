package org.example.observer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Day8 HTTP Tool 的可控测试数据源。
 *
 * <p>
 * 仅供本地学习环境使用。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
public class ServiceObserverController {

    private final AtomicReference<Day8ResponseMode> mode = new AtomicReference<>(Day8ResponseMode.NORMAL);

    /** Day10 与 Day8 故障注入独立；仅供本地实验，不代表生产监控。 */
    private final AtomicReference<DependencyScenario> dependencyScenario = new AtomicReference<>(DependencyScenario.NORMAL);

    public enum DependencyScenario { NORMAL, DB_SLOW, RPC_TIMEOUT, UNAVAILABLE }

    /** 切换 DB/RPC 实验场景，同时调整服务指标及辅助日志。Kafka 固定场景由主项目 Eval 提供。 */
    @GetMapping("/internal/day10/scenario/{scenario}")
    public DependencyScenario changeDependencyScenario(@PathVariable DependencyScenario scenario) {
        dependencyScenario.set(scenario);
        return scenario;
    }

    /** 同一响应返回 DB/RPC 指标；不可用时用 503 触发 Harness 受控重试。 */
    @GetMapping(value = "/services/{serviceName}/dependencies", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getDependencyStatus(@PathVariable String serviceName) {
        DependencyScenario scenario = dependencyScenario.get();
        if (scenario == DependencyScenario.UNAVAILABLE) {
            return ResponseEntity.status(503).build();
        }
        log.info("GET /services/{}/dependencies, scenario = {}", serviceName, scenario);
        return ResponseEntity.ok(Map.of("serviceName", serviceName,
                "database", Map.of("p99Ms", scenario == DependencyScenario.DB_SLOW ? 2500.0 : 30.0,
                        "activeConnections", scenario == DependencyScenario.DB_SLOW ? 49 : 10, "maxConnections", 50),
                "rpc", Map.of("dependency", "payment-gateway",
                        "p99Ms", scenario == DependencyScenario.RPC_TIMEOUT ? 3200.0 : 80.0,
                        "timeoutRate", scenario == DependencyScenario.RPC_TIMEOUT ? 0.35 : 0.001)));
    }

    /**
     * 手工切换 Day8 实验场景。
     */
    @GetMapping("/internal/day8/mode/{mode}")
    public Day8ResponseMode changeMode(@PathVariable Day8ResponseMode mode) {
        this.mode.set(mode);
        return mode;
    }

    /**
     * 服务实时指标。
     */
    @GetMapping(value = "/services/{serviceName}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getServiceStatus(@PathVariable String serviceName) {

        log.info("GET /services/{}/status, mode = {}", serviceName, mode.get());

        DependencyScenario scenario = dependencyScenario.get();
        if (mode.get() == Day8ResponseMode.NORMAL && scenario != DependencyScenario.NORMAL) {
            return ResponseEntity.ok(new ServiceStatusResponse(serviceName, 45.0, 62.0, 135, 200, true));
        }

        return switch (mode.get()) {

            case MISSING_FIELD ->
                /*
                 * 故意删除 memoryPercent，
                 * 验证 Client 能识别不完整响应。
                 */
                    ResponseEntity.ok(
                            Map.of(
                                    "serviceName", serviceName,
                                    "cpuPercent", 55.0,
                                    "threadPoolActive", 200,
                                    "threadPoolMax", 200,
                                    "healthy", true
                            )
                    );

            case WRONG_CONTENT_TYPE ->
                    ResponseEntity
                            .ok()
                            .contentType(MediaType.TEXT_PLAIN)
                            .body("intentionally wrong content type");

            default ->
                    ResponseEntity.ok(
                            new ServiceStatusResponse(
                                    serviceName,
                                    55.0,
                                    68.0,
                                    200,
                                    200,
                                    true
                            )
                    );
        };
    }

    /**
     * 错误日志查询。
     */
    @GetMapping(
            value = "/services/{serviceName}/logs",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public List<ErrorLogResponse> queryLogs(@PathVariable String serviceName, @RequestParam int minutes) {

        log.info("GET /services/{}/logs?minutes={}, mode = {}", serviceName, minutes, mode.get());

        if (mode.get() == Day8ResponseMode.NORMAL && dependencyScenario.get() != DependencyScenario.NORMAL) {
            // 故意不含 DB/RPC 数值，验证不能只依赖日志文字判断根因。
            return List.of(new ErrorLogResponse("WARN", "message processing latency increased"));
        }

        if (mode.get() == Day8ResponseMode.LARGE_PAYLOAD) {
            return List.of(
                    new ErrorLogResponse(
                            "ERROR",
                            "X".repeat(12_000)
                    )
            );
        }

        return List.of(
                new ErrorLogResponse(
                        "ERROR",
                        "TaskRejectedException: "
                                + "executor queue is full"
                )
        );
    }

    public record ServiceStatusResponse(
            String serviceName,
            double cpuPercent,
            double memoryPercent,
            int threadPoolActive,
            int threadPoolMax,
            boolean healthy
    ) {
    }

    public record ErrorLogResponse(
            String level,
            String message
    ) {
    }
}
