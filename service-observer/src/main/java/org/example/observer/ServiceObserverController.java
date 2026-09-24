package org.example.observer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service Observer 控制器：提供 HTTP 接口模拟生产监控 API。
 *
 * <p>设计背景：Day8 引入 HTTP Tool 后，主工程的 Agent 需要通过 HTTP 调用外部服务获取指标。
 * 本控制器提供可控的测试数据源，支持故障注入和场景切换。</p>
 *
 * <p>接口说明：
 * <ul>
 *   <li>{@code GET /api/v1/services/{serviceName}/status} — 查询服务指标</li>
 *   <li>{@code GET /api/v1/services/{serviceName}/dependencies} — 查询依赖指标</li>
 *   <li>{@code GET /api/v1/services/{serviceName}/logs} — 查询错误日志</li>
 *   <li>{@code GET /api/v1/internal/day8/mode/{mode]] — 切换 Day8 响应模式</li>
 *   <li>{@code GET /api/v1/internal/day10/scenario/{scenario]] — 切换 Day10 依赖场景</li>
 * </ul>
 * </p>
 *
 * <p>仅供本地学习环境使用，不代表生产监控。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
public class ServiceObserverController {

    /** Day8 响应模式：控制服务指标的返回格式。 */
    private final AtomicReference<Day8ResponseMode> mode = new AtomicReference<>(Day8ResponseMode.NORMAL);

    /** Day10 依赖场景：控制依赖指标的返回内容，与 Day8 故障注入独立。 */
    private final AtomicReference<DependencyScenario> dependencyScenario = new AtomicReference<>(DependencyScenario.NORMAL);

    /**
     * 依赖场景枚举：模拟不同的依赖故障情况。
     */
    public enum DependencyScenario {
        /** 正常：数据库和 RPC 指标正常。 */
        NORMAL,
        /** DB_SLOW：数据库响应慢，连接池接近耗尽。 */
        DB_SLOW,
        /** RPC_TIMEOUT：下游 RPC 大量超时。 */
        RPC_TIMEOUT,
        /** UNAVAILABLE：依赖服务不可用，返回 503。 */
        UNAVAILABLE
    }

    /**
     * 切换 DB/RPC 实验场景，同时调整服务指标及辅助日志。
     * Kafka 固定场景由主项目 Eval 提供。
     *
     * @param scenario 依赖场景
     * @return 切换后的场景
     */
    @GetMapping("/internal/day10/scenario/{scenario}")
    public DependencyScenario changeDependencyScenario(@PathVariable DependencyScenario scenario) {
        dependencyScenario.set(scenario);
        return scenario;
    }

    /**
     * 查询依赖指标：数据库 P99、连接池、下游 RPC P99、超时率。
     * 同一响应返回 DB/RPC 指标；不可用时用 503 触发 Harness 受控重试。
     *
     * @param serviceName 服务名称
     * @return 依赖指标或 503 错误
     */
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
     * 切换 Day8 响应模式：控制服务指标的返回格式。
     *
     * @param mode 响应模式
     * @return 切换后的模式
     */
    @GetMapping("/internal/day8/mode/{mode}")
    public Day8ResponseMode changeMode(@PathVariable Day8ResponseMode mode) {
        this.mode.set(mode);
        return mode;
    }

    /**
     * 查询服务实时指标：CPU、内存、线程池等。
     *
     * <p>根据 Day8 响应模式返回不同格式：
     * <ul>
     *   <li>NORMAL — 正常返回完整指标</li>
     *   <li>MISSING_FIELD — 故意删除 memoryPercent，验证 Client 能识别不完整响应</li>
     *   <li>WRONG_CONTENT_TYPE — 返回非 application/json Content-Type</li>
     * </ul>
     * </p>
     *
     * @param serviceName 服务名称
     * @return 服务指标或错误响应
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
     * 查询错误日志：最近一段时间的错误摘要。
     *
     * <p>根据 Day8 响应模式返回不同内容：
     * <ul>
     *   <li>NORMAL — 正常返回错误日志</li>
     *   <li>LARGE_PAYLOAD — 返回超大 Payload，测试截断机制</li>
     * </ul>
     * </p>
     *
     * @param serviceName 服务名称
     * @param minutes     向前查询分钟数
     * @return 错误日志列表
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

    /**
     * 服务状态响应 DTO：包含 CPU、内存、线程池和健康状态。
     */
    public record ServiceStatusResponse(
            String serviceName,
            double cpuPercent,
            double memoryPercent,
            int threadPoolActive,
            int threadPoolMax,
            boolean healthy
    ) {
    }

    /**
     * 错误日志响应 DTO：包含日志级别和消息内容。
     */
    public record ErrorLogResponse(
            String level,
            String message
    ) {
    }
}
