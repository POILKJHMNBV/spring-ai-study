package org.example.ai.harness;

import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.security.ConversationSecurity;
import org.jspecify.annotations.NullMarked;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.IntSupplier;

/**
 * 工具回调防护包装器：为原始 {@link ToolCallback} 添加超时、重试、脱敏和截断能力。
 *
 * <p>核心职责：
 * <ul>
 *   <li>超时控制：工具调用在独立线程池执行，超时后取消并返回受控错误</li>
 *   <li>自动重试：可重试的错误类型（如超时、连接失败）最多重试指定次数</li>
 *   <li>敏感信息脱敏：工具返回的日志可能包含凭据，进入模型 Context 前先脱敏</li>
 *   <li>结果截断：防止超大工具结果挤占 Context Window，超过 8000 字符自动截断</li>
 *   <li>全链路追踪：记录每次工具调用的耗时、尝试次数和状态</li>
 * </ul>
 * </p>
 *
 * <p>设计要点：
 * <ul>
 *   <li>重试间隔遵循 {@link ToolFailure#delay()}，限流时遵守 Retry-After 头</li>
 *   <li>截断后的结果会明确标记 {@code [TOOL_RESULT_TRUNCATED]}，防止模型误认为完整数据</li>
 *   <li>中断（InterruptedException）必须停止等待且不能再次调用下游</li>
 * </ul>
 * </p>
 */
@NullMarked
@Slf4j
public class GuardedToolCallback implements ToolCallback {
    /**
     * 单次 Tool Result 最多允许进入模型 Context 的字符数。
     * 防止日志查询等工具一次返回超大文本，挤占 Context Window。
     */
    private static final int MAX_TOOL_RESULT_CHARS = 8_000;

    /** 代理工具回调：实际执行工具逻辑的底层实现。 */
    private final ToolCallback delegate;
    /** 工具执行线程池：隔离工具调用，支持超时取消。 */
    private final ExecutorService executor;
    /** 单次工具调用超时时间。 */
    private final Duration timeout;
    /** 最大重试次数：失败后最多重试的次数（不含首次调用）。 */
    private final int maxRetries;
    /** 追踪记录器：记录每次工具调用的详细信息。 */
    private final TraceRecorder trace;
    /** 当前步骤供应器：用于追踪记录。 */
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
                    executor.submit(() -> toolContext == null ?
                            delegate.call(toolInput) :
                            delegate.call(toolInput, toolContext));

            try {

                String rawResult = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);

                long elapsedMs = elapsedMs(start);

                /*
                 * Tool 返回的数据可能包含敏感日志，
                 * 进入模型 Context 前先做基础脱敏。
                 */
                String sanitizedResult = ConversationSecurity.sanitizeSensitiveText(rawResult);

                String result = truncateToolResult(toolName, sanitizedResult);

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
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                trace.recordTool(stepSupplier.getAsInt(), toolName, toolInput,
                        ToolFailure.classify(e).observation(), elapsedMs(start), attempt, "INTERRUPTED");
                throw new IllegalStateException("Tool execution interrupted: " + toolName, e);
            } catch (TimeoutException | ExecutionException e) {
                future.cancel(true);
                ToolFailure failure = ToolFailure.classify(e);
                boolean retry = failure.retryable() && attempt < maxAttempts;
                trace.recordTool(stepSupplier.getAsInt(), toolName, toolInput,
                        failure.observation(), elapsedMs(start), attempt,
                        failure.type().name() + (retry ? "_RETRY" : ""));
                if (!retry) {
                    return failure.observation();
                }
                // 限流时遵守 Retry-After；中断必须停止等待且不能再次调用下游。
                try {
                    TimeUnit.NANOSECONDS.sleep(failure.delay().toNanos());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    trace.recordTool(stepSupplier.getAsInt(), toolName, toolInput,
                            failure.observation(), elapsedMs(start), attempt, "RETRY_INTERRUPTED");
                    throw new IllegalStateException("Tool retry interrupted", interrupted);
                }
            }
        }
        log.error("Unexpected tool execution state: {}", toolName);
        throw new IllegalStateException("Unexpected tool execution state");
    }

    private long elapsedMs(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    /**
     * 限制 Tool Result 的最大长度。
     *
     * @param toolName Tool 名称
     * @param result Tool 原始结果
     * @return 可安全放入模型 Context 的结果
     */
    private String truncateToolResult(String toolName , String result) {
        if (result.length() <= MAX_TOOL_RESULT_CHARS) {
            return result;
        }

        log.warn("Tool result too long for {} ({}, truncated to {} chars)",
                toolName,
                result.length(),
                MAX_TOOL_RESULT_CHARS
        );
        String preview = result.substring(0, MAX_TOOL_RESULT_CHARS);

        /*
         * 明确告诉模型：当前结果不是完整数据。
         * 禁止模型把截断内容误认为完整结果。
         */
        return """
            [TOOL_RESULT_TRUNCATED]
            originalChars=%d
            returnedChars=%d
            nextPage=UNAVAILABLE
            preview:
            %s
            """.formatted(
                result.length(),
                preview.length(),
                preview
        );
    }
}
