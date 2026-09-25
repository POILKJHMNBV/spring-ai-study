package org.example.ai.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.metadata.EmptyUsage;

import java.util.concurrent.TimeUnit;

/**
 * Day13 标准可观测性入口。仅使用固定名称和有限状态标签，绝不把问题、工具参数、
 * 工具输出、异常消息或会话标识写进指标和 Observation。
 * TraceRecorder 继续承担面向业务的逐步记录，本类负责可聚合指标和父子调用链。
 */
public final class AgentTelemetry {
    private final MeterRegistry meters;
    private final ObservationRegistry observations;

    /**
     * 注入 Boot 管理的指标与 Observation 注册表。
     */
    public AgentTelemetry(MeterRegistry meters, ObservationRegistry observations) {
        this.meters = meters;
        this.observations = observations;
    }

    /**
     * 开始一个标准 Observation；当前作用域决定它的父节点。
     */
    public Observation start(String name) {
        return Observation.start(name, observations);
    }

    /**
     * 记录请求结束状态、耗时、步骤数及策略失败数。
     */
    public void agentFinished(String status, int steps, long nanos) {
        String safe = switch (status) {
            case "COMPLETED", "FAILED", "POLICY_REJECTED", "STEP_LIMIT_EXCEEDED" -> status;
            default -> "FAILED";
        };
        Counter.builder("agent.requests").tag("status", safe).register(meters).increment();
        if (!"COMPLETED".equals(safe)) {
            Counter.builder("agent.failures").tag("status", safe).register(meters).increment();
        }
        if ("POLICY_REJECTED".equals(safe)) {
            Counter.builder("agent.policy.rejects").register(meters).increment();
        }
        Timer.builder("agent.latency").publishPercentiles(0.5, 0.95).publishPercentileHistogram().register(meters).record(nanos, TimeUnit.NANOSECONDS);
        DistributionSummary.builder("agent.steps").register(meters).record(steps);
    }

    /**
     * 模型耗时以直方图保存，可用于计算 P50/P95；未知 token 不写入样本。
     */
    public void modelFinished(long nanos, Usage usage) {
        Timer.builder("llm.request.latency").publishPercentiles(0.5, 0.95).publishPercentileHistogram().register(meters)
                .record(nanos, TimeUnit.NANOSECONDS);
        if (usage == null || usage instanceof EmptyUsage) {
            Counter.builder("llm.usage.unknown").register(meters).increment();
        } else {
            DistributionSummary.builder("llm.prompt.tokens").register(meters).record(usage.getPromptTokens());
            DistributionSummary.builder("llm.completion.tokens").register(meters).record(usage.getCompletionTokens());
        }
    }

    /**
     * 记录一次完整工具调用及其最终状态。
     */
    public void toolFinished(String status, long nanos, boolean mcp) {
        String safe = "SUCCESS".equals(status) ? "SUCCESS" : "FAILED";
        Counter.builder("agent.tool.calls").register(meters).increment();
        Counter.builder("tool.calls").tag("status", safe).register(meters).increment();
        if (!"SUCCESS".equals(safe)) Counter.builder("tool.failures").register(meters).increment();
        Timer.builder("tool.latency").publishPercentiles(0.5, 0.95).publishPercentileHistogram().register(meters).record(nanos, TimeUnit.NANOSECONDS);
        if (mcp) {
            Counter.builder("mcp.calls").tag("status", safe).register(meters).increment();
            if (!"SUCCESS".equals(safe))
                Counter.builder("mcp.failure").tag("phase", "runtime").register(meters).increment();
            Timer.builder("mcp.latency").publishPercentiles(0.5, 0.95).publishPercentileHistogram().register(meters).record(nanos, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 工具超时和重试均按实际发生次数记录。
     */
    public void timeout() {
        Counter.builder("tool.timeout").register(meters).increment();
    }

    /**
     * 一次重新提交计作一次重试。
     */
    public void retry() {
        Counter.builder("tool.retry").register(meters).increment();
    }

    /**
     * MCP 工具发现失败时也计入 MCP 失败，避免只统计已进入远程调用的故障。
     */
    public void mcpDiscoveryFailed() {
        Counter.builder("mcp.failure").tag("phase", "discovery").register(meters).increment();
    }

    /**
     * 检索结果数量只作为数值样本，绝不以文档名或问题作为标签。
     */
    public void retrievalFinished(long nanos, int candidates, int selected) {
        Timer.builder("rag.retrieve.latency").publishPercentiles(0.5, 0.95).publishPercentileHistogram().register(meters)
                .record(nanos, TimeUnit.NANOSECONDS);
        DistributionSummary.builder("rag.candidate.count").register(meters).record(candidates);
        DistributionSummary.builder("rag.final.count").register(meters).record(selected);
    }

    /**
     * 检索异常仍计入耗时；候选数未知时不写入虚假的零样本。
     */
    public void retrievalFailed(long nanos) {
        Timer.builder("rag.retrieve.latency").publishPercentiles(0.5, 0.95).publishPercentileHistogram().register(meters)
                .record(nanos, TimeUnit.NANOSECONDS);
    }
}
