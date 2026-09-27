package org.example.ai.controller;

import org.example.ai.diagnosis.DiagnosisReport;
import org.example.ai.diagnosis.DiagnosisStatus;
import org.example.ai.diagnosis.Fact;
import org.example.ai.diagnosis.Hypothesis;
import org.example.ai.diagnosis.NextAction;
import org.example.ai.harness.AgentRunResult;
import org.example.ai.harness.AgentRunner;
import org.example.ai.service.ChatService;
import org.example.ai.tool.KafkaToolMode;
import org.example.ai.tool.mock.MockScenario;
import org.example.ai.tool.mock.OpsMockDataProvider;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** 确认 /ai/agent 与 /ai/eval 直接返回包含完整报告的 JSON 对象。 */
class ChatControllerStructuredJsonTest {
    /** 两个接口都必须暴露运行状态、展示回答、Trace 和全部报告字段。 */
    @Test
    void agentAndEvalEndpointsSerializeTheCompleteAgentRunResult() throws Exception {
        AgentRunner runner = mock(AgentRunner.class);
        OpsMockDataProvider dataProvider = mock(OpsMockDataProvider.class);
        AgentRunResult result = new AgentRunResult(AgentRunResult.RunStatus.COMPLETED,
                "状态：DIAGNOSED\n摘要：连接池压力升高", 2, List.of(), sampleReport());
        when(runner.run(anyString(), anyString(), anyBoolean(), eq(KafkaToolMode.MCP))).thenReturn(result);
        when(runner.run(anyString(), anyString(), anyBoolean())).thenReturn(result);
        MockMvc mvc = standaloneSetup(new ChatController(mock(ChatService.class), runner, dataProvider)).build();

        mvc.perform(get("/ai/agent").param("userPrompt", "排查服务")
                        .param("conversationId", "controller-agent").param("memoryEnabled", "false"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.answer").value("状态：DIAGNOSED\n摘要：连接池压力升高"))
                .andExpect(jsonPath("$.completedSteps").value(2))
                .andExpect(jsonPath("$.trace").isArray())
                .andExpect(jsonPath("$.report.status").value("DIAGNOSED"))
                .andExpect(jsonPath("$.report.facts[0].source").value("getServiceStatus"))
                .andExpect(jsonPath("$.report.hypotheses[0].confidence").value(0.9))
                .andExpect(jsonPath("$.report.nextActions[0].requiresApproval").value(true))
                .andExpect(jsonPath("$.report.missingInformation[0]").value("数据库版本"));

        mvc.perform(get("/ai/eval").param("userPrompt", "排查服务")
                        .param("conversationId", "controller-eval")
                        .param("mockScenario", MockScenario.E01_THREAD_POOL_SATURATED.name())
                        .param("memoryEnabled", "false"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.report.summary").value("连接池压力升高"))
                .andExpect(jsonPath("$.report.hypotheses[0].evidence[0]").value("活跃连接接近上限"));

        verify(dataProvider).useScenario(MockScenario.E01_THREAD_POOL_SATURATED);
    }

    /**
     * 为接口 JSON 序列化构造包含所有嵌套结构的固定报告。
     *
     * @return 包含事实、假设、动作和缺失信息的诊断报告
     */
    private DiagnosisReport sampleReport() {
        return new DiagnosisReport(DiagnosisStatus.DIAGNOSED, "连接池压力升高",
                List.of(new Fact("活跃连接接近上限", "getServiceStatus")),
                List.of(new Hypothesis("数据库连接池紧张", 0.9, List.of("活跃连接接近上限"))),
                List.of(new NextAction("核对连接池设置", true)), List.of("数据库版本"));
    }
}
