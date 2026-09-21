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