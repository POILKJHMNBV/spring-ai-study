package org.example.ai.harness;

import jakarta.annotation.Nullable;
import org.jspecify.annotations.NullMarked;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.IntSupplier;

/**
 * ToolCallback包装类
 */
@NullMarked
public class GuardedToolCallback implements ToolCallback {

    /**
     * 代理 ToolCallback
     */
    private final ToolCallback delegate;

    /**
     * 线程池
     */
    private final ExecutorService executor;

    /**
     * 超时时间
     */
    private final Duration timeout;

    /**
     * 最大重试次数
     */
    private final int maxRetries;

    /**
     * 跟踪记录器
     */
    private final TraceRecorder trace;

    /**
     * 步骤Supplier
     */
    private final IntSupplier stepSupplier;

    public GuardedToolCallback(
            ToolCallback delegate,
            ExecutorService executor,
            Duration timeout,
            int maxRetries,
            TraceRecorder trace,
            IntSupplier stepSupplier
    ) {
        this.delegate = delegate;
        this.executor = executor;
        this.timeout = timeout;
        this.maxRetries = maxRetries;
        this.trace = trace;
        this.stepSupplier = stepSupplier;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return execute(toolInput, null);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        return execute(toolInput, toolContext);
    }

    private String execute(String toolInput, @Nullable ToolContext toolContext) {

        String toolName = delegate.getToolDefinition().name();

        int maxAttempts = 1 + maxRetries;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long start = System.nanoTime();
            Future<String> future =
                    executor.submit(() -> {

                        if (toolContext == null) {
                            return delegate.call(toolInput);
                        }

                        return delegate.call(toolInput, toolContext);
                    });

            try {

                String result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);

                long elapsedMs = elapsedMs(start);

                trace.recordTool(
                        stepSupplier.getAsInt(),
                        toolName,
                        toolInput,
                        result,
                        elapsedMs,
                        attempt,
                        "SUCCESS"
                );

                return result;
            } catch (TimeoutException e) {

                future.cancel(true);

                long elapsedMs = elapsedMs(start);

                // 第一次 timeout，可以再试一次
                if (attempt < maxAttempts) {

                    trace.recordTool(
                            stepSupplier.getAsInt(),
                            toolName,
                            toolInput,
                            "timeout, retrying",
                            elapsedMs,
                            attempt,
                            "TIMEOUT_RETRY"
                    );

                    continue;
                }

                // 重试后仍然失败：
                // 将失败作为 Observation 返回给 LLM
                String errorResult = """
                        {
                          "status": "ERROR",
                          "errorType": "TIMEOUT",
                          "message": "tool execution timed out"
                        }
                        """;

                trace.recordTool(
                        stepSupplier.getAsInt(),
                        toolName,
                        toolInput,
                        errorResult,
                        elapsedMs,
                        attempt,
                        "TIMEOUT"
                );

                return errorResult;
            } catch (InterruptedException e) {

                Thread.currentThread().interrupt();

                throw new IllegalStateException(
                        "Tool execution interrupted: "
                                + toolName,
                        e
                );
            } catch (ExecutionException e) {

                long elapsedMs =
                        elapsedMs(start);

                trace.recordTool(
                        stepSupplier.getAsInt(),
                        toolName,
                        toolInput,
                        e.getCause() == null
                                ? e.getMessage()
                                : e.getCause().getMessage(),
                        elapsedMs,
                        attempt,
                        "FAILED"
                );

                throw new IllegalStateException(
                        "Tool execution failed: "
                                + toolName,
                        e.getCause()
                );
            }
        }

        throw new IllegalStateException(
                "Unexpected tool execution state"
        );
    }

    private long elapsedMs(long start) {
        return TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - start
        );
    }
}
