package org.example.ai.harness;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.ObservationView;
import org.example.ai.observability.AgentTelemetry;
import org.example.ai.rag.KnowledgeRetriever;
import org.example.ai.tool.AgentToolProvider;
import org.example.ai.tool.KafkaToolMode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Day13 确定性 Agent 观测测试：直接调用真实 AgentRunner，替换外部模型和数据源。
 * 每条路径的业务状态、指标和 Observation 父子关系必须一致。
 */
class Day13AgentTelemetryTest {
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ObservationRegistry observations = ObservationRegistry.create();
    private final CapturingHandler capture = new CapturingHandler();
    private final AgentToolProvider provider = mock(AgentToolProvider.class);
    private final ChatModel model = mock(ChatModel.class);
    private final KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
    private final ChatMemory memory = mock(ChatMemory.class);

    /** 为每次测试构造真实执行引擎与观测处理器，保持模型响应完全确定。 */
    private AgentRunner runner() {
        observations.observationConfig().observationHandler(capture);
        when(provider.getToolCallbacks(KafkaToolMode.LOCAL)).thenReturn(new ToolCallback[0]);
        when(provider.getToolCallbacks(KafkaToolMode.MCP)).thenReturn(new ToolCallback[0]);
        when(model.getOptions()).thenReturn(OllamaChatOptions.builder().build());
        when(retriever.retrieve(anyString())).thenReturn(List.of());
        return new AgentRunner(ToolCallingManager.builder().build(), provider, model,
                new ExecutionPolicy(), retriever, memory, new AgentTelemetry(meters, observations));
    }

    /** 无工具的正常回答仍需记录请求、步数和检索/模型/最终回答子节点。 */
    @Test
    void successfulRequestRecordsCoreMetersAndParentedObservations() {
        when(model.call(any(Prompt.class))).thenReturn(response("证据不足"));
        AgentRunResult result = runner().run("排查服务", "day13-success", false);
        assertEquals(AgentRunResult.RunStatus.COMPLETED, result.status());
        assertEquals(1, result.completedSteps());
        assertEquals(1, meters.get("agent.requests").counter().count());
        assertEquals(0, count("agent.failures"));
        assertEquals(1, meters.get("agent.latency").timer().count());
        assertEquals(1, meters.get("llm.request.latency").timer().count());
        // 此处 Retriever 被替换为 mock；实际检索 Timer 由真实 Retriever 集成测试验证。
        assertTrue(capture.events.stream().anyMatch(e -> e.name().contains("agent") && e.parent() == null));
        assertTrue(capture.events.stream().anyMatch(e -> e.name().contains("llm") && e.parent() != null));
        assertTrue(capture.events.stream().anyMatch(e -> e.name().contains("rag") && e.parent() != null));
        assertTrue(meters.find("llm.prompt.tokens").summaries().isEmpty(),
                "没有可用用量时不能伪造为零 token 样本");
        assertTrue(meters.find("llm.completion.tokens").summaries().isEmpty());
        assertEquals(1, count("llm.usage.unknown"), "EmptyUsage 应计为未知用量");
    }

    /** 模型抛异常时 Agent 返回受控失败，失败和耗时指标均只记一次。 */
    @Test
    void failedModelCallIncrementsFailureOnce() {
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("synthetic failure"));
        AgentRunResult result = runner().run("排查服务", "day13-failed", false);
        assertEquals(AgentRunResult.RunStatus.FAILED, result.status());
        assertEquals(1, count("agent.requests"));
        assertEquals(1, count("agent.failures"));
        assertEquals(1, meters.get("llm.request.latency").timer().count());
    }

    /** 模型返回 null 时应形成受控失败，模型耗时与未知用量各记一次并关闭请求观察。 */
    @Test
    void nullModelResponseReturnsControlledFailureAndClosesObservations() {
        when(model.call(any(Prompt.class))).thenReturn(null);
        AgentRunResult result = runner().run("排查服务", "day13-null-response", false);
        assertEquals(AgentRunResult.RunStatus.FAILED, result.status());
        assertEquals(1, result.completedSteps());
        assertEquals(1, count("agent.requests"));
        assertEquals(1, count("agent.failures"));
        assertEquals(1, meters.get("llm.request.latency").timer().count());
        assertEquals(1, count("llm.usage.unknown"));
        assertTrue(capture.events.stream().anyMatch(e -> e.name().equals("llm.request")
                && e.parent().equals("agent.request")));
        assertTrue(capture.events.stream().anyMatch(e -> e.name().equals("agent.request")
                && e.parent() == null));
        assertNull(observations.getCurrentObservation(), "空响应后不能残留请求 Observation scope");
    }

    /** 未授权工具在执行前被策略阻止，应留下拒绝指标且不记录工具执行成功。 */
    @Test
    void policyRejectIsCountedWithoutExecutingTool() {
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "deleteDatabase", "{}"))).build()))));
        AgentRunResult result = runner().run("执行未经授权工具", "day13-policy", false);
        assertEquals(AgentRunResult.RunStatus.POLICY_REJECTED, result.status());
        assertEquals(1, count("agent.policy.rejects"));
        assertEquals(0, count("tool.calls"));
    }

    /** MCP 工具发现失败同样属于 Agent 请求失败，不能遗漏请求级指标。 */
    @Test
    void discoveryFailureIsCountedBeforeModelCall() {
        AgentRunner runner = runner();
        when(provider.getToolCallbacks(KafkaToolMode.MCP)).thenThrow(new IllegalStateException("unavailable"));
        AgentRunResult result = runner.run("查询 Kafka", "day13-discovery", false, KafkaToolMode.MCP);
        assertEquals(AgentRunResult.RunStatus.FAILED, result.status());
        assertEquals(1, count("agent.requests"));
        assertEquals(1, count("agent.failures"));
        verify(model, never()).call(any(Prompt.class));
    }

    /** 检索抛出异常时外围请求观测仍结束，且失败数不能漏记。 */
    @Test
    void retrievalExceptionClosesFailedRequestObservation() {
        AgentRunner runner = runner();
        when(retriever.retrieve(anyString())).thenThrow(new IllegalStateException("synthetic retrieval error"));
        assertThrows(IllegalStateException.class,
                () -> runner.run("排查服务", "day13-rag-failed", false));
        assertEquals(1, count("agent.requests"));
        assertEquals(1, count("agent.failures"));
        assertTrue(capture.events.stream().anyMatch(e -> e.name().equals("rag.retrieve")
                && e.parent().equals("agent.request")));
        assertNull(observations.getCurrentObservation(), "检索异常后不能泄漏请求 Observation scope");
    }

    /** 已知模型元数据必须作为 token 数值样本保留，不能误计为未知。 */
    @Test
    void knownModelUsageRecordsTokenSummaries() {
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(12);
        when(usage.getCompletionTokens()).thenReturn(7);
        ChatResponse response = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
        when(response.getResult()).thenReturn(new Generation(new AssistantMessage("完成")));
        when(response.getMetadata().getUsage()).thenReturn(usage);
        when(model.call(any(Prompt.class))).thenReturn(response);
        assertEquals(AgentRunResult.RunStatus.COMPLETED,
                runner().run("排查服务", "day13-known-usage", false).status());
        assertEquals(12, meters.get("llm.prompt.tokens").summary().totalAmount());
        assertEquals(7, meters.get("llm.completion.tokens").summary().totalAmount());
        assertEquals(0, count("llm.usage.unknown"));
    }

    /** 构造不依赖网络的 Spring AI 模型响应。 */
    private ChatResponse response(String body) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(body))));
    }

    /** 对一个指标名聚合所有安全低基数标签组合。 */
    private double count(String name) {
        return meters.find(name).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    /** 捕获 Observation 的实际父指针，验证手写 Agent 的上下文关联。 */
    private static class CapturingHandler implements ObservationHandler<Observation.Context> {
        private final List<ObservationEvent> events = new ArrayList<>();

        /** 所有上下文均可观测，测试只读取标准 Observation 生命周期。 */
        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        /** 完成时读取父 Observation；子节点必须指向仍然有效的请求根节点。 */
        @Override
        public void onStop(Observation.Context context) {
            ObservationView parent = context.getParentObservation();
            events.add(new ObservationEvent(context.getName(),
                    parent == null ? null : parent.getContextView().getName()));
        }
    }

    /** 只保存低基数名称和父名称，不采集用户输入或工具参数。 */
    private record ObservationEvent(String name, String parent) { }
}
