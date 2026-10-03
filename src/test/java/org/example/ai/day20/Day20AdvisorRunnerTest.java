package org.example.ai.day20;

import org.example.ai.harness.*;
import org.example.ai.rag.KnowledgeRetriever;
import org.example.ai.tool.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.*;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Day20 官方 Advisor 循环边界测试；模型固定响应，执行真实 ToolCallingManager 与 GuardedToolCallback。 */
class Day20AdvisorRunnerTest {
    /** 没有实时事实的合法不足证据报告。 */
    private static final String REPORT = """
        {"status":"INSUFFICIENT_EVIDENCE","summary":"缺少指标，无法确定",
        "facts":[],"hypotheses":[],"nextActions":[],"missingInformation":["服务指标"]}
        """;

    /** 混合合法与非法请求必须整批拒绝，合法工具也不能先产生副作用。 */
    @Test void rejectsWholeMixedBatchBeforeAnyCallback() {
        try (Fixture f = fixture(List.of(toolResponse(call("getServiceStatus", "{\"serviceName\":\"payment-service\"}"),
                call("deleteService", "{}"))))) {
            var result = f.runner.run("排查", "mixed", false);
            assertEquals(AgentRunResult.RunStatus.POLICY_REJECTED, result.status());
            assertEquals(0, f.tools.calls.get());
            assertTrue(result.trace().stream().anyMatch(t -> t.type()==TraceRecorder.EventType.POLICY));
        }
    }

    /** 六轮模型总预算耗尽以后不得发起第七轮，也不能继续调用工具。 */
    @Test void limitsAutomaticLoopToSixModelCalls() {
        List<ChatResponse> responses = new ArrayList<>();
        for (int i=1;i<=6;i++) responses.add(toolResponse(call("queryErrorLogs",
                "{\"serviceName\":\"payment-service\",\"minutes\":"+i+"}")));
        try (Fixture f = fixture(responses)) {
            var result=f.runner.run("排查", "limit", false);
            assertEquals(AgentRunResult.RunStatus.STEP_LIMIT_EXCEEDED,result.status());
            assertEquals(6,f.modelCalls.get());
            assertEquals(6,result.completedSteps());
            assertEquals(6,f.tools.calls.get());
        }
    }

    /** 空响应共享一次修复预算，并且修复请求必须完全移除工具。 */
    @Test void repairsMissingResponseWithoutExposingTools() {
        List<ChatResponse> responses = new ArrayList<>();
        responses.add(null); responses.add(response(REPORT));
        try (Fixture f = fixture(responses)) {
            var result=f.runner.run("排查", "missing", false);
            assertEquals(AgentRunResult.RunStatus.COMPLETED,result.status());
            assertEquals(2,result.completedSteps());
            OllamaChatOptions options=(OllamaChatOptions) f.prompts.get(1).getOptions();
            assertTrue(options.getToolCallbacks().isEmpty());
        }
    }

    /** 修复阶段模型仍请求工具时，硬拒绝且不能执行回调。 */
    @Test void rejectsToolCallsDuringRepair() {
        try (Fixture f = fixture(List.of(response("invalid"),toolResponse(call("getServiceStatus",
                "{\"serviceName\":\"payment-service\"}"))))) {
            var result=f.runner.run("排查", "repair-tool", false);
            assertEquals(AgentRunResult.RunStatus.FAILED,result.status());
            assertEquals(0,f.tools.calls.get());
            assertTrue(result.trace().stream().anyMatch(t -> "FAILED".equals(t.status()) && "REPAIR".equals(t.name())));
        }
    }

    /** 连续重复请求必须在第二次真实调用以前拒绝。 */
    @Test void rejectsRepeatedCalls() {
        ChatResponse repeated=toolResponse(call("getServiceStatus","{\"serviceName\":\"payment-service\"}"));
        try (Fixture f=fixture(List.of(repeated,repeated))) {
            var result=f.runner.run("排查", "repeat", false);
            assertEquals(AgentRunResult.RunStatus.POLICY_REJECTED,result.status());
            assertEquals(1,f.tools.calls.get());
        }
    }

    /** 无效报告在第六轮出现时不能再发起修复；之前五轮工具仍有完整轨迹。 */
    @Test void repairSharesSixStepBudget() {
        List<ChatResponse> responses=new ArrayList<>();
        for(int i=1;i<=5;i++) responses.add(toolResponse(call("queryErrorLogs",
                "{\"serviceName\":\"payment-service\",\"minutes\":"+i+"}")));
        responses.add(response("invalid"));
        try(Fixture f=fixture(responses)) {
            var result=f.runner.run("排查", "repair-limit", false);
            assertEquals(AgentRunResult.RunStatus.FAILED,result.status());
            assertEquals(6,f.modelCalls.get());
            assertTrue(result.trace().stream().anyMatch(t -> "LIMIT".equals(t.status())));
        }
    }

    /** 成功会话只写入自己的 Memory，另一个会话不得看到它的用户实体。 */
    @Test void isolatesMemoryAndPreservesFailureReport() {
        try(Fixture f=fixture(List.of(response(REPORT),response(REPORT)))) {
            assertEquals(AgentRunResult.RunStatus.COMPLETED,f.runner.run("payment-service", "session-a",true).status());
            f.runner.run("刚才这个服务", "session-b",true);
            assertFalse(f.prompts.get(1).getInstructions().stream().anyMatch(m ->
                    m instanceof UserMessage && "payment-service".equals(m.getText())));
            assertEquals(2,f.memory.get("session-a").size());
        }
        String failed="""
            {"status":"FAILED","summary":"报告失败原因","facts":[],"hypotheses":[],
            "nextActions":[],"missingInformation":["未能验证指标"]}
            """;
        try(Fixture f=fixture(List.of(response("invalid"),response(failed)))) {
            var result=f.runner.run("排查", "failed",true);
            assertEquals(AgentRunResult.RunStatus.FAILED,result.status());
            assertEquals("报告失败原因",result.report().summary());
            assertTrue(f.memory.get("failed").isEmpty());
            assertTrue(result.trace().stream().anyMatch(t -> "REPAIR".equals(t.name()) && "FAILED".equals(t.status())));
        }
    }

    /** 模型异常必须保留发生阶段、真实步数及停止轨迹，不泄漏异常文本。 */
    @Test void recordsModelFailureStage() {
        try(Fixture f=fixture(List.of(response(REPORT)))) {
            when(f.model.call(any(Prompt.class))).thenThrow(new IllegalStateException("secret-token"));
            var result=f.runner.run("排查", "model-error",false);
            assertEquals(AgentRunResult.RunStatus.FAILED,result.status());
            assertEquals(1,result.completedSteps());
            assertTrue(result.trace().stream().anyMatch(t -> "MODEL_FAILED".equals(t.status())));
            assertFalse(result.toString().contains("secret-token"));
        }
    }

    /**
     * 按序提供响应并装配真实官方循环。
     * @param responses 模型返回序列，可包含 null 用于空响应测试
     * @return 可关闭的独立测试夹具
     */
    private Fixture fixture(List<ChatResponse> responses) {
        ChatModel model=mock(ChatModel.class);
        when(model.getOptions()).thenReturn(OllamaChatOptions.builder().build());
        AtomicInteger calls=new AtomicInteger();
        List<Prompt> prompts=new ArrayList<>();
        when(model.call(any(Prompt.class))).thenAnswer(inv -> {
            prompts.add(inv.getArgument(0));
            int index=calls.getAndIncrement();
            return responses.get(Math.min(index,responses.size()-1));
        });
        CountingTools tools=new CountingTools();
        AgentToolProvider provider=mock(AgentToolProvider.class);
        when(provider.getToolCallbacks(KafkaToolMode.LOCAL)).thenReturn(ToolCallbacks.from(tools));
        KnowledgeRetriever retriever=mock(KnowledgeRetriever.class);
        when(retriever.retrieve(anyString())).thenReturn(List.of());
        ChatMemory memory=MessageWindowChatMemory.builder().build();
        return new Fixture(new AdvisorAgentRunner(ToolCallingManager.builder().build(),provider,model,
                new ExecutionPolicy(),retriever,memory),model,calls,tools,prompts,memory);
    }

    /**
     * 构造正文响应。
     * @param text 固定正文
     * @return 官方响应对象
     */
    private ChatResponse response(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
    /**
     * 构造工具请求。
     * @param name 工具名
     * @param arguments JSON 参数
     * @return 官方工具调用记录
     */
    private AssistantMessage.ToolCall call(String name,String arguments) {
        return new AssistantMessage.ToolCall(UUID.randomUUID().toString(),"function",name,arguments);
    }
    /**
     * 构造一批工具请求响应。
     * @param calls 一轮请求中的全部调用
     * @return 带工具调用的响应
     */
    private ChatResponse toolResponse(AssistantMessage.ToolCall... calls) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(calls)).build())));
    }
    /**
     * 独立测试资源集合。
     * @param runner 被测执行器
     * @param model 可控制异常的模型桩
     * @param modelCalls 实际模型调用计数
     * @param tools 实际工具计数
     * @param prompts 捕获的输入
     * @param memory 独立记忆
     */
    private record Fixture(AdvisorAgentRunner runner,ChatModel model,AtomicInteger modelCalls,
            CountingTools tools,List<Prompt> prompts,ChatMemory memory) implements AutoCloseable {
        /** 释放测试工具线程。 */
        @Override public void close() { runner.close(); }
    }
    /** 通过注解注册的计数工具，任何调用都产生可观察计数。 */
    public static final class CountingTools {
        final AtomicInteger calls=new AtomicInteger();
        /**
         * 服务只读查询。
         * @param serviceName 查询服务
         * @return 固定数据
         */
        @Tool public String getServiceStatus(String serviceName) { calls.incrementAndGet(); return "{}"; }
        /**
         * 日志只读查询。
         * @param serviceName 查询服务
         * @param minutes 查询窗口
         * @return 固定数据
         */
        @Tool public String queryErrorLogs(String serviceName,int minutes) { calls.incrementAndGet(); return "{}"; }
    }
}
