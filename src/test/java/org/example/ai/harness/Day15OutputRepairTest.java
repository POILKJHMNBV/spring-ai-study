package org.example.ai.harness;

import org.example.ai.diagnosis.DiagnosisStatus;
import org.example.ai.rag.KnowledgeRetriever;
import org.example.ai.tool.AgentToolProvider;
import org.example.ai.tool.KafkaToolMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 用固定脚本模型通过真实 AgentRunner 验证 Day15 输出校验修复的边界。 */
class Day15OutputRepairTest {
    private static final String CONVERSATION = "day15-output-repair";
    private static final String INSUFFICIENT = """
            {"status":"INSUFFICIENT_EVIDENCE","summary":"目前证据不足",
             "facts":[],"hypotheses":[],"nextActions":[],
             "missingInformation":["需要最近五分钟的错误日志"]}
            """;
    private final List<AgentRunner> runners = new CopyOnWriteArrayList<>();

    /** 结束本测试创建的执行器，避免工具线程残留影响后续案例。 */
    @AfterEach
    void shutdownRunners() {
        runners.forEach(AgentRunner::shutdownToolExecutor);
    }

    /** 非 JSON 首次输出可在一次受限修复后恢复成合法证据不足报告。 */
    @Test
    void malformedOutputIsRepairedIntoValidInsufficientEvidenceReport() {
        ChatMemory memory = memory(CONVERSATION);
        TestFixture fixture = fixture(List.of(response("这不是 JSON"), response(INSUFFICIENT)), memory, false);

        AgentRunResult result = fixture.runner().run("排查未知事件", CONVERSATION, true);

        assertEquals(AgentRunResult.RunStatus.COMPLETED, result.status());
        assertEquals(DiagnosisStatus.INSUFFICIENT_EVIDENCE, result.report().status());
        assertEquals(2, fixture.modelCalls().get(), "首次输出和唯一修复各占一个模型调用");
        assertTrue(result.trace().stream().anyMatch(event -> event.type() == TraceRecorder.EventType.VALIDATION
                && "OUTPUT_VALIDATION".equals(event.name()) && "REJECTED".equals(event.status())));
        assertTrue(result.trace().stream().anyMatch(event -> event.type() == TraceRecorder.EventType.VALIDATION
                && "OUTPUT_VALIDATION".equals(event.name()) && "PASS".equals(event.status())));
        assertTrue(result.trace().stream().anyMatch(event -> event.type() == TraceRecorder.EventType.VALIDATION
                && "REPAIR".equals(event.name()) && "SUCCEEDED".equals(event.status())));
        assertTrue(((OllamaChatOptions) fixture.prompts().get(1).getOptions()).getToolCallbacks().isEmpty(),
                "修复请求不得携带可执行工具");
        OllamaChatOptions repairOptions = (OllamaChatOptions) fixture.prompts().get(1).getOptions();
        assertEquals(false, repairOptions.getThinkOption().toJsonValue(),
                "格式修复必须关闭额外思考，防止消耗全部上下文后无正文");
        assertEquals(4096, repairOptions.getNumPredict(), "唯一修复的生成预算必须有界");
        verify(memory, times(2)).add(eq(CONVERSATION), any(Message.class));
    }

    /** 工具返回后续模型调用及唯一一次修复都必须看到同一轮目录中的真实快照。 */
    @Test
    void toolDirectoryIsVisibleAfterToolCallAndDuringRepair() {
        ChatMemory memory = memory(CONVERSATION);
        TestFixture fixture = fixture(List.of(
                toolCallResponse(), response("invalid report"), response(diagnosedToolIdReport())), memory, true);

        AgentRunResult result = fixture.runner().run("检查 payment-service", CONVERSATION, true);

        assertEquals(AgentRunResult.RunStatus.COMPLETED, result.status());
        assertEquals(3, fixture.modelCalls().get());
        assertEquals(1, fixture.toolCalls().get());
        assertDirectoryInPrompt(fixture.prompts().get(1));
        assertDirectoryInPrompt(fixture.prompts().get(2));
        verify(memory, times(2)).add(eq(CONVERSATION), any(Message.class));
    }

    /** 业务置信度越界会触发同一修复路径，并可恢复成基于真实工具结果的报告。 */
    @Test
    void outOfRangeConfidenceIsRepairedAgainstRealToolEvidence() {
        ChatMemory memory = memory(CONVERSATION);
        String invalidConfidence = diagnosedReport(1.2);
        String validDiagnosis = diagnosedReport(0.8);
        TestFixture fixture = fixture(List.of(
                toolCallResponse(), response(invalidConfidence), response(validDiagnosis)), memory, true);

        AgentRunResult result = fixture.runner().run("检查 payment-service", CONVERSATION, true);

        assertEquals(AgentRunResult.RunStatus.COMPLETED, result.status());
        assertEquals(0.8, result.report().hypotheses().get(0).confidence(), 0.000001);
        assertEquals(3, fixture.modelCalls().get());
        assertEquals(1, fixture.toolCalls().get());
        assertTrue(fixture.prompts().get(2).getOptions() instanceof OllamaChatOptions
                        && ((OllamaChatOptions) fixture.prompts().get(2).getOptions()).getToolCallbacks().isEmpty(),
                "业务修复请求同样必须禁用工具");
        verify(memory, times(2)).add(eq(CONVERSATION), any(Message.class));
    }

    /** 连续两次非法输出后必须停止，且失败轮次不得写入 Memory。 */
    @Test
    void consecutiveInvalidOutputsStopAfterSingleRepairWithoutSavingMemory() {
        ChatMemory memory = memory(CONVERSATION);
        TestFixture fixture = fixture(List.of(response("bad output one"), response("bad output two")), memory, false);

        AgentRunResult result = fixture.runner().run("排查服务", CONVERSATION, true);

        assertEquals(AgentRunResult.RunStatus.FAILED, result.status());
        assertEquals(2, fixture.modelCalls().get(), "非法输出最多进行一次修复调用");
        assertTrue(result.trace().stream().anyMatch(event -> event.type() == TraceRecorder.EventType.VALIDATION
                && "REPAIR".equals(event.name()) && "FAILED".equals(event.status())));
        verify(memory, never()).add(eq(CONVERSATION), any(Message.class));
    }

    /** 空正文恢复和结构化输出修复共用预算，不能在一次请求内分别各重试一次。 */
    @Test
    void emptyAnswerRecoveryAndOutputRepairShareOneRecoveryBudget() {
        ChatMemory memory = memory(CONVERSATION);
        TestFixture fixture = fixture(List.of(response("  "), response("still not JSON"), response(INSUFFICIENT)),
                memory, false);

        AgentRunResult result = fixture.runner().run("排查服务", CONVERSATION, true);

        assertEquals(AgentRunResult.RunStatus.FAILED, result.status());
        assertEquals(2, fixture.modelCalls().get(), "空正文已消耗本轮唯一的恢复调用");
        verify(memory, never()).add(eq(CONVERSATION), any(Message.class));
    }

    /** 修复请求若试图发起工具调用，应直接拒绝且不能触发任何工具副作用。 */
    @Test
    void repairToolCallIsRejectedWithoutExecutingTool() {
        ChatMemory memory = memory(CONVERSATION);
        TestFixture fixture = fixture(List.of(response("bad JSON"), toolCallResponse()), memory, true);

        AgentRunResult result = fixture.runner().run("排查服务", CONVERSATION, true);

        assertNotEquals(AgentRunResult.RunStatus.COMPLETED, result.status());
        assertEquals(2, fixture.modelCalls().get());
        assertEquals(0, fixture.toolCalls().get(), "修复回复不能进入 ToolCallingManager");
        assertTrue(((OllamaChatOptions) fixture.prompts().get(1).getOptions()).getToolCallbacks().isEmpty(),
                "修复模型请求本身也不能拥有工具回调");
        assertTrue(result.trace().stream().anyMatch(event -> event.type() == TraceRecorder.EventType.VALIDATION
                && "REPAIR".equals(event.name()) && "FAILED".equals(event.status())));
        verify(memory, never()).add(eq(CONVERSATION), any(Message.class));
    }

    /** 修复模型调用计入 Agent 原有步数上限，抵达第六步后不能额外请求模型。 */
    @Test
    void repairCallsShareTheExistingSixStepBudget() {
        ChatMemory memory = memory(CONVERSATION);
        List<ChatResponse> script = List.of(
                toolCallResponse("getServiceStatus", "{\"serviceName\":\"payment-service\"}"),
                toolCallResponse("getKafkaStatus", "{\"topic\":\"order-topic\"}"),
                toolCallResponse("getDependencyStatus", "{\"serviceName\":\"payment-service\"}"),
                toolCallResponse("queryErrorLogs", "{\"serviceName\":\"payment-service\",\"minutes\":5}"),
                toolCallResponse("getServiceStatus", "{\"serviceName\":\"payment-service\"}"),
                response("bad output at the last allowed step"),
                response(INSUFFICIENT));
        TestFixture fixture = fixture(script, memory, true);

        AgentRunResult result = fixture.runner().run("排查服务", CONVERSATION, true);

        assertNotEquals(AgentRunResult.RunStatus.COMPLETED, result.status());
        assertEquals(6, fixture.modelCalls().get(), "repair 不能突破 Agent 的六步上限");
        assertTrue(result.completedSteps() <= 6);
        assertTrue(result.trace().stream().anyMatch(event -> event.type() == TraceRecorder.EventType.VALIDATION
                && "REPAIR".equals(event.name()) && "LIMIT".equals(event.status())),
                "没有剩余步数时应记录修复预算受限");
        assertTrue(result.trace().stream().noneMatch(event -> event.type() == TraceRecorder.EventType.VALIDATION
                && "REPAIR".equals(event.name()) && "STARTED".equals(event.status())),
                "修复预算不足时不应记录虚假的修复启动");
        verify(memory, never()).add(eq(CONVERSATION), any(Message.class));
    }

    /** 为被测调用创建只在内存中工作的会话记忆桩。
     * @param conversationId 本次单测使用的会话 ID
     * @return 只为目标会话返回空历史的记忆桩
     */
    private ChatMemory memory(String conversationId) {
        ChatMemory memory = mock(ChatMemory.class);
        when(memory.get(conversationId)).thenReturn(List.of());
        return memory;
    }

    /** 创建按调用顺序返回固定响应的模型桩和真实 AgentRunner。
     * @param responses 按模型调用顺序排列的固定回复
     * @param memory 被测会话记忆组件
     * @param includeTools 是否注册具备计数能力的工具回调
     * @return 包含执行器、模型调用计数和工具调用计数的夹具
     */
    private TestFixture fixture(List<ChatResponse> responses, ChatMemory memory, boolean includeTools) {
        AtomicInteger modelCalls = new AtomicInteger();
        SideEffectTool sideEffectTool = new SideEffectTool();
        List<Prompt> prompts = new CopyOnWriteArrayList<>();
        ToolCallback[] callbacks = includeTools ? ToolCallbacks.from(sideEffectTool) : new ToolCallback[0];
        AgentToolProvider provider = mock(AgentToolProvider.class);
        when(provider.getToolCallbacks(KafkaToolMode.LOCAL)).thenReturn(callbacks);
        when(provider.getToolCallbacks(KafkaToolMode.MCP)).thenReturn(callbacks);
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(OllamaChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            int index = modelCalls.getAndIncrement();
            prompts.add(invocation.getArgument(0));
            return responses.get(Math.min(index, responses.size() - 1));
        });
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        when(retriever.retrieve(anyString())).thenReturn(List.of());
        AgentRunner runner = new AgentRunner(ToolCallingManager.builder().build(), provider, model,
                new ExecutionPolicy(), retriever, memory);
        runners.add(runner);
        return new TestFixture(runner, modelCalls, sideEffectTool.calls, prompts);
    }

    /** 创建只带模型正文的固定响应。
     * @param text 模型输出正文
     * @return 包含单条 AssistantMessage 的模型响应
     */
    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** 创建指定合法工具名的模型调用响应。
     * @param name Agent 白名单中的工具名
     * @param arguments 工具调用的 JSON 参数
     * @return 携带单条工具调用的模型响应
     */
    private ChatResponse toolCallResponse(String name, String arguments) {
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall("day15-call", "function", name, arguments);
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(call)).build())));
    }

    /** 创建修复响应中的服务查询工具调用样例。
     * @return 携带 getServiceStatus 请求的模型响应
     */
    private ChatResponse toolCallResponse() {
        return toolCallResponse("getServiceStatus", "{\"serviceName\":\"payment-service\"}");
    }

    /** 构造证据引用真实服务工具结果的诊断报告。
     * @param confidence 要写入的置信度
     * @return 使用固定工具 JSON 事实的完整报告 JSON
     */
    private String diagnosedReport(double confidence) {
        return """
                {"status":"DIAGNOSED","summary":"线程池状态可用",
                 "facts":[{"statement":"{\\"healthy\\":true}","source":"getServiceStatus"}],
                 "hypotheses":[{"cause":"服务健康","confidence":%s,"evidence":["{\\"healthy\\":true}"]}],
                 "nextActions":[],"missingInformation":[]}
                """.formatted(confidence);
    }

    /**
     * 构造引用本次 getServiceStatus 工具调用目录 ID 的有效诊断报告。
     *
     * @return 事实与假设证据均引用 TOOL-1 的 JSON 报告
     */
    private String diagnosedToolIdReport() {
        return """
                {"status":"DIAGNOSED","summary":"服务当前健康",
                 "facts":[{"statement":"TOOL-1","source":"getServiceStatus"}],
                 "hypotheses":[{"cause":"服务检查未发现异常","confidence":0.6,"evidence":["TOOL-1"]}],
                 "nextActions":[],"missingInformation":[]}
                """;
    }

    /**
     * 断言模型提示中的当前目录包含 ID、原工具名称及完整 JSON 快照。
     *
     * @param prompt 工具调用后的正常提示或输出校验修复提示
     */
    private void assertDirectoryInPrompt(Prompt prompt) {
        String text = prompt.getInstructions().stream().map(Message::getText)
                .reduce((left, right) -> left + "\n" + right).orElse("");
        assertTrue(text.contains("[CURRENT_TOOL_EVIDENCE_CATALOG]"), "提示应包含本轮目录标记");
        assertTrue(text.contains("TOOL-1"), "提示应包含工具目录 ID");
        assertTrue(text.contains("getServiceStatus"), "提示应包含实际工具来源");
        assertTrue(text.contains("{\"healthy\":true}"), "提示应包含工具返回的完整快照");
    }

    /** 一个计数工具，用于证明修复回复未被执行。 */
    private static final class SideEffectTool {
        private final AtomicInteger calls = new AtomicInteger();

        /** 模拟只读服务查询，同时累加调用次数供安全断言使用。
         * @param serviceName 被查询的服务名称
         * @return 固定 JSON 形状的健康状态
         */
        @Tool(description = "Day15 测试中的只读服务查询")
        public Object getServiceStatus(
                @ToolParam(description = "服务名称") String serviceName) {
            calls.incrementAndGet();
            return Map.of("healthy", true);
        }

        /** 模拟只读 Kafka 查询，为 Agent 步数上限场景提供有效回调。
         * @param topic 被查询的 Kafka Topic
         * @return 固定 JSON 形状的消费状态
         */
        @Tool(description = "Day15 测试中的只读 Kafka 查询")
        public Object getKafkaStatus(
                @ToolParam(description = "Topic 名称") String topic) {
            calls.incrementAndGet();
            return Map.of("lag", 0);
        }

        /** 模拟只读依赖查询，为 Agent 步数上限场景提供有效回调。
         * @param serviceName 被查询的服务名称
         * @return 固定 JSON 形状的依赖状态
         */
        @Tool(description = "Day15 测试中的只读依赖查询")
        public Object getDependencyStatus(
                @ToolParam(description = "服务名称") String serviceName) {
            calls.incrementAndGet();
            return Map.of("healthy", true);
        }

        /** 模拟只读日志查询，为 Agent 步数上限场景提供有效回调。
         * @param serviceName 被查询的服务名称
         * @param minutes 向前查询的时间范围
         * @return 空日志数组
         */
        @Tool(description = "Day15 测试中的只读日志查询")
        public Object queryErrorLogs(
                @ToolParam(description = "服务名称") String serviceName,
                @ToolParam(description = "查询分钟数") int minutes) {
            calls.incrementAndGet();
            return List.of();
        }
    }

    /** 脚本模型调用计数、工具副作用计数和实际收到的 Prompt。
     * @param runner 被测真实 AgentRunner
     * @param modelCalls 脚本模型实际调用数
     * @param toolCalls 被执行的测试工具调用数
     * @param prompts 模型每次收到的完整 Prompt
     */
    private record TestFixture(AgentRunner runner, AtomicInteger modelCalls, AtomicInteger toolCalls,
                               List<Prompt> prompts) { }
}
