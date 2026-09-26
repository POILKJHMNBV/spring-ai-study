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

import static org.example.ai.common.Constants.*;

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
            case TELEMETRY_AGENT_STATUS_COMPLETED, TELEMETRY_AGENT_STATUS_FAILED,
                    TELEMETRY_AGENT_STATUS_POLICY_REJECTED,
                    TELEMETRY_AGENT_STATUS_STEP_LIMIT_EXCEEDED -> status;
            default -> TELEMETRY_AGENT_STATUS_FAILED;
        };
        Counter.builder(METRIC_AGENT_REQUESTS).tag(TELEMETRY_TAG_STATUS, safe).register(meters).increment();
        if (!TELEMETRY_AGENT_STATUS_COMPLETED.equals(safe)) {
            Counter.builder(METRIC_AGENT_FAILURES).tag(TELEMETRY_TAG_STATUS, safe).register(meters).increment();
        }
        if (TELEMETRY_AGENT_STATUS_POLICY_REJECTED.equals(safe)) {
            Counter.builder(METRIC_AGENT_POLICY_REJECTS).register(meters).increment();
        }
        Timer.builder(METRIC_AGENT_LATENCY).publishPercentiles(TELEMETRY_PERCENTILE_P50, TELEMETRY_PERCENTILE_P95).publishPercentileHistogram().register(meters).record(nanos, TimeUnit.NANOSECONDS);
        DistributionSummary.builder(METRIC_AGENT_STEPS).register(meters).record(steps);
    }

    /**
     * 模型耗时以直方图保存，可用于计算 P50/P95；未知 token 不写入样本。
     */
    public void modelFinished(long nanos, Usage usage) {
        Timer.builder(METRIC_LLM_REQUEST_LATENCY).publishPercentiles(TELEMETRY_PERCENTILE_P50, TELEMETRY_PERCENTILE_P95).publishPercentileHistogram().register(meters)
                .record(nanos, TimeUnit.NANOSECONDS);
        if (usage == null || usage instanceof EmptyUsage) {
            Counter.builder(METRIC_LLM_USAGE_UNKNOWN).register(meters).increment();
        } else {
            DistributionSummary.builder(METRIC_LLM_PROMPT_TOKENS).register(meters).record(usage.getPromptTokens());
            DistributionSummary.builder(METRIC_LLM_COMPLETION_TOKENS).register(meters).record(usage.getCompletionTokens());
        }
    }

    /**
     * 记录一次完整工具调用及其最终状态。
     */
    public void toolFinished(String status, long nanos, boolean mcp) {
        String safe = TELEMETRY_TOOL_OUTCOME_SUCCESS.equals(status)
                ? TELEMETRY_TOOL_OUTCOME_SUCCESS : TELEMETRY_TOOL_OUTCOME_FAILED;
        Counter.builder(METRIC_AGENT_TOOL_CALLS).register(meters).increment();
        Counter.builder(METRIC_TOOL_CALLS).tag(TELEMETRY_TAG_STATUS, safe).register(meters).increment();
        if (!TELEMETRY_TOOL_OUTCOME_SUCCESS.equals(safe)) Counter.builder(METRIC_TOOL_FAILURES).register(meters).increment();
        Timer.builder(METRIC_TOOL_LATENCY).publishPercentiles(TELEMETRY_PERCENTILE_P50, TELEMETRY_PERCENTILE_P95).publishPercentileHistogram().register(meters).record(nanos, TimeUnit.NANOSECONDS);
        if (mcp) {
            Counter.builder(METRIC_MCP_CALLS).tag(TELEMETRY_TAG_STATUS, safe).register(meters).increment();
            if (!TELEMETRY_TOOL_OUTCOME_SUCCESS.equals(safe))
                Counter.builder(METRIC_MCP_FAILURE).tag(TELEMETRY_TAG_PHASE, TELEMETRY_MCP_FAILURE_PHASE_RUNTIME).register(meters).increment();
            Timer.builder(METRIC_MCP_LATENCY).publishPercentiles(TELEMETRY_PERCENTILE_P50, TELEMETRY_PERCENTILE_P95).publishPercentileHistogram().register(meters).record(nanos, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 工具超时和重试均按实际发生次数记录。
     */
    public void timeout() {
        Counter.builder(METRIC_TOOL_TIMEOUT).register(meters).increment();
    }

    /**
     * 一次重新提交计作一次重试。
     */
    public void retry() {
        Counter.builder(METRIC_TOOL_RETRY).register(meters).increment();
    }

    /**
     * MCP 工具发现失败时也计入 MCP 失败，避免只统计已进入远程调用的故障。
     */
    public void mcpDiscoveryFailed() {
        Counter.builder(METRIC_MCP_FAILURE).tag(TELEMETRY_TAG_PHASE, TELEMETRY_MCP_FAILURE_PHASE_DISCOVERY).register(meters).increment();
    }

    /**
     * 检索结果数量只作为数值样本，绝不以文档名或问题作为标签。
     */
    public void retrievalFinished(long nanos, int candidates, int selected) {
        Timer.builder(METRIC_RAG_RETRIEVE_LATENCY).publishPercentiles(TELEMETRY_PERCENTILE_P50, TELEMETRY_PERCENTILE_P95).publishPercentileHistogram().register(meters)
                .record(nanos, TimeUnit.NANOSECONDS);
        DistributionSummary.builder(METRIC_RAG_CANDIDATE_COUNT).register(meters).record(candidates);
        DistributionSummary.builder(METRIC_RAG_FINAL_COUNT).register(meters).record(selected);
    }

    /**
     * 检索异常仍计入耗时；候选数未知时不写入虚假的零样本。
     */
    public void retrievalFailed(long nanos) {
        Timer.builder(METRIC_RAG_RETRIEVE_LATENCY).publishPercentiles(TELEMETRY_PERCENTILE_P50, TELEMETRY_PERCENTILE_P95).publishPercentileHistogram().register(meters)
                .record(nanos, TimeUnit.NANOSECONDS);
    }
}
