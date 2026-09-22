package org.example.ai.eval;

import org.example.ai.harness.AgentRunner;
import org.example.ai.harness.AgentRunResult;
import org.example.ai.tool.client.DependencyMetricsClient;
import org.example.ai.tool.client.LogQueryClient;
import org.example.ai.tool.client.InvalidToolResponseException;
import org.example.ai.tool.mock.OpsMockDataProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 Ollama 验收：去掉日志捷径，并保存完整模型回答和轨迹供复核。 */
@SpringBootTest(properties = {"app.ops.data-source=MOCK", "spring.ai.mcp.client.enabled=false",
        "spring.ai.ollama.chat.temperature=0.0", "spring.ai.ollama.chat.seed=42"})
class Day10DependencyIT {
    @Autowired private AgentRunner runner;
    @Autowired private OpsMockDataProvider data;
    @MockitoBean private LogQueryClient logs;
    @MockitoBean private DependencyMetricsClient dependencies;

    @BeforeEach
    void setup() {
        when(logs.queryErrorLogs(anyString(), anyInt())).thenReturn(List.of());
        when(dependencies.getDependencyStatus(anyString())).thenAnswer(call -> data.getDependencyStatus(call.getArgument(0)));
    }

    @Test
    void diagnosesE03AndE04WithoutLogHints() throws Exception {
        List<AgentRunResult> results = new ArrayList<>();
        try {
            for (var evalCase : AgentEvalCases.all().stream().filter(c -> List.of("E03", "E04").contains(c.id())).toList()) {
                data.useScenario(evalCase.scenario());
                var result = runner.run(evalCase.prompt(), "day10-" + evalCase.id(), false);
                results.add(result);
                assertEquals(AgentRunResult.RunStatus.COMPLETED, result.status());
                var score = new AgentEvaluator().evaluate(evalCase, result, 0);
                assertTrue(score.mainJudgmentPass(), result.answer());
                assertTrue(score.toolSelectionPass(), "必须收集服务、Kafka、依赖三类证据");
                assertTrue(score.evidenceFaithfulnessPass(), "实时数值必须能从工具结果追溯");
            }
        } finally {
            save("without-logs", results);
            data.reset();
        }
    }

    @Test
    void missingDependencyRemainsUnconfirmed() throws Exception {
        when(dependencies.getDependencyStatus(anyString())).thenThrow(new InvalidToolResponseException("依赖数据缺失"));
        var result = runner.run("payment-service 的 order-topic 消费变慢，请重点确认数据库或 RPC 是否为根因。",
                "day10-missing", false);
        save("missing", List.of(result));
        assertEquals(AgentRunResult.RunStatus.COMPLETED, result.status());
        verify(dependencies).getDependencyStatus("payment-service");
        assertTrue(result.trace().stream().anyMatch(e -> "getDependencyStatus".equals(e.name())
                && e.status().contains("INVALID_RESPONSE")));
        assertTrue(List.of("无法确认", "不能确认", "待验证", "证据不足", "无法确定").stream()
                .anyMatch(result.answer()::contains), result.answer());
        assertFalse(result.answer().contains("2500"));
        assertFalse(result.answer().contains("3200"));
    }

    private void save(String name, List<AgentRunResult> results) throws Exception {
        Path path = Path.of("target", "eval-results", "day10-" + name + ".json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(results));
    }
}
