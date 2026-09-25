package org.example.ai.harness;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.ObservationView;
import org.example.ai.observability.AgentTelemetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Day13 工具观测测试：使用真实 GuardedToolCallback 的执行器、超时和重试机制。
 * 断言指标标签只包含固定安全值，原始参数与异常内容不能进入指标。
 */
class Day13ToolTelemetryTest {
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ObservationRegistry observations = ObservationRegistry.create();
    private final List<String> parentNames = new CopyOnWriteArrayList<>();
    private final List<String> failedObservations = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AgentTelemetry telemetry = new AgentTelemetry(meters, observations);

    /** 结束测试时释放可取消工具使用的工作线程。 */
    @AfterEach
    void stopExecutor() throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }

    /** 重试一次后成功，尝试次数、重试次数和总调用次数不得混淆。 */
    @Test
    void retryAndSuccessAreCountedWithoutSensitiveTags() throws Exception {
        FlakyTool tool = new FlakyTool(observations);
        GuardedToolCallback guarded = guard(tool, Duration.ofSeconds(1), 1);
        String result = Observation.createNotStarted("agent.request", observations)
                .observe(() -> guarded.call("{\"token\":\"secret-day13\"}"));
        assertEquals("\"ok\"", result, "Spring AI ToolCallback 会将 String 返回值序列化为 JSON 字符串");
        assertEquals(2, tool.calls.get());
        assertEquals(1, count("tool.calls"));
        assertEquals(1, count("tool.retry"));
        assertEquals(0, count("tool.failures"));
        assertEquals(1, meters.find("tool.latency").timers().stream().mapToLong(t -> t.count()).sum());
        assertTrue(parentNames.stream().anyMatch("agent.request"::equals),
                "跨 Executor 的 Tool Observation 应继续属于原 Agent 请求");
        assertEquals("tool.attempt", tool.observedWorkerObservation,
                "真正执行工具的线程应继承本次 Tool Attempt Observation");
        assertNull(executor.submit(observations::getCurrentObservation).get(1, TimeUnit.SECONDS),
                "工具任务结束后工作线程不应泄漏 Observation scope");
        assertSafeMeterIds();
    }

    /** 两次均超过 Harness 截止时间，必须记录 timeout 和最终失败。 */
    @Test
    void timeoutRetriesOnceAndReportsFinalFailure() {
        SlowTool tool = new SlowTool();
        GuardedToolCallback guarded = guard(tool, Duration.ofMillis(50), 1);
        String result = Observation.createNotStarted("agent.request", observations)
                .observe(() -> guarded.call("{\"token\":\"secret-day13\"}"));
        assertTrue(result.contains("TIMEOUT"));
        assertEquals(2, tool.calls.get());
        assertEquals(1, count("tool.calls"));
        assertEquals(1, count("tool.retry"));
        assertEquals(2, count("tool.timeout"));
        assertEquals(1, count("tool.failures"));
        assertEquals(1, meters.find("tool.latency").timers().stream().mapToLong(t -> t.count()).sum());
        assertSafeMeterIds();
    }

    /** 执行器拒绝新任务时也必须关闭 Tool 观察并记录一次最终失败。 */
    @Test
    void rejectedExecutorStillRecordsFailedToolCall() {
        executor.shutdownNow();
        GuardedToolCallback guarded = guard(new SlowTool(), Duration.ofMillis(50), 0);
        assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> guarded.call("{}"));
        assertEquals(1, count("tool.calls"));
        assertEquals(1, count("tool.failures"));
        assertEquals(0, count("tool.retry"));
        assertTrue(failedObservations.contains("tool.attempt"), "拒绝提交的尝试应以失败状态结束");
        assertTrue(failedObservations.contains("tool.call"), "完整工具调用应以失败状态结束");
        assertNull(observations.getCurrentObservation(), "拒绝提交后不能残留 Tool scope");
    }

    /** 工作线程被占用时，排队的工具尝试超时后必须取消，不得稍后执行或留下未关闭的观察。 */
    @Test
    void queuedAttemptIsCancelledBeforeDelegateRuns() throws Exception {
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.submit(() -> {
            occupied.countDown();
            release.await();
            return null;
        });
        assertTrue(occupied.await(1, TimeUnit.SECONDS));
        SlowTool tool = new SlowTool();
        GuardedToolCallback guarded = guard(tool, Duration.ofMillis(50), 0);
        try {
            assertTrue(guarded.call("{}").contains("TIMEOUT"));
        } finally {
            release.countDown();
        }
        assertNull(executor.submit(observations::getCurrentObservation).get(1, TimeUnit.SECONDS));
        assertEquals(0, tool.calls.get(), "已取消的排队任务不能在工作线程空闲后运行");
        assertEquals(1, count("tool.timeout"));
        assertEquals(0, count("tool.retry"));
        assertEquals(1, count("tool.failures"));
        assertTrue(failedObservations.contains("tool.attempt"));
        assertTrue(failedObservations.contains("tool.call"));
        assertNull(observations.getCurrentObservation());
    }

    /** 调用方等待工具时被中断，应取消当前任务并立即结束，不能产生虚假的下一次重试。 */
    @Test
    void callerInterruptionCancelsAttemptWithoutRetry() throws Exception {
        BlockingTool tool = new BlockingTool();
        GuardedToolCallback guarded = guard(tool, Duration.ofSeconds(5), 2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                guarded.call("{}");
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "day13-interrupted-caller");
        caller.start();
        try {
            assertTrue(tool.entered.await(1, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(2_000);
            assertFalse(caller.isAlive(), "中断应及时结束调用方等待");
            assertInstanceOf(IllegalStateException.class, failure.get());
            assertEquals(1, tool.calls.get());
            assertEquals(0, count("tool.retry"));
            assertEquals(0, count("tool.timeout"));
            assertEquals(1, count("tool.calls"));
            assertEquals(1, count("tool.failures"));
            assertTrue(failedObservations.contains("tool.attempt"));
            assertTrue(failedObservations.contains("tool.call"));
        } finally {
            caller.interrupt();
            tool.release.countDown();
            caller.join(2_000);
        }
    }

    /** 注册可接受所有 Observation 的处理器，并以人工根节点验证跨线程父子关系。 */
    private GuardedToolCallback guard(Object tool, Duration timeout, int retries) {
        observations.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            /** 接收所有观察上下文，避免过滤掉需要验证的失败节点。 */
            @Override
            public boolean supportsContext(Observation.Context context) { return true; }

            /** 结束时记录父节点和错误状态，检查被取消或拒绝的尝试确实结束。 */
            @Override
            public void onStop(Observation.Context context) {
                if (context.getName().contains("tool")) {
                    ObservationView parent = context.getParentObservation();
                    parentNames.add(parent == null ? null : parent.getContextView().getName());
                    if (context.getError() != null) failedObservations.add(context.getName());
                }
            }
        });
        return new GuardedToolCallback(ToolCallbacks.from(tool)[0], executor, timeout, retries,
                new TraceRecorder(), () -> 1, telemetry);
    }

    /** 遍历所有 Meter ID 的 tags，敏感内容不能出现在名称、键或值中。 */
    private void assertSafeMeterIds() {
        for (Meter meter : meters.getMeters()) {
            String id = meter.getId().toString();
            assertFalse(id.contains("secret-day13"), id);
            assertFalse(id.contains("token"), id);
        }
    }

    /** 聚合同名但不同低基数 tag 的 Counter。 */
    private double count(String name) {
        return meters.find(name).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    /** 首次调用返回可重试网络错误，第二次返回成功。 */
    public static class FlakyTool {
        final AtomicInteger calls = new AtomicInteger();
        final ObservationRegistry observations;
        volatile String observedWorkerObservation;

        /** 接收与包装器相同的 Registry，检测执行器中的真实上下文。 */
        public FlakyTool(ObservationRegistry observations) { this.observations = observations; }

        /** 第一次抛可重试错误，第二次返回成功，同时读取工作线程内当前 Observation。 */
        @Tool(description = "测试可恢复失败")
        public String flaky() {
            Observation active = observations.getCurrentObservation();
            observedWorkerObservation = active == null ? null : active.getContext().getName();
            if (calls.incrementAndGet() == 1) {
                throw new java.io.UncheckedIOException(new java.net.ConnectException("secret-day13"));
            }
            return "ok";
        }
    }

    /** 响应时间始终超过 50 毫秒，线程中断代表超时取消成功。 */
    public static class SlowTool {
        final AtomicInteger calls = new AtomicInteger();
        /** 持续阻塞直到 Harness 超时并中断工作线程。 */
        @Tool(description = "测试工具超时")
        public String slow() throws InterruptedException {
            calls.incrementAndGet();
            Thread.sleep(5_000);
            return "unexpected";
        }
    }

    /** 通过显式入口闩锁，确保测试中断发生在工具开始运行之后。 */
    public static class BlockingTool {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        /** 保持工作线程占用，直到调用方取消或测试清理释放。 */
        @Tool(description = "测试调用方中断")
        public String block() throws InterruptedException {
            calls.incrementAndGet();
            entered.countDown();
            release.await();
            return "unexpected";
        }
    }
}
