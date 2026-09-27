package org.example.ai.harness;

import org.example.ai.diagnosis.DiagnosisStatus;
import org.example.ai.rag.KnowledgeRetriever;
import org.example.ai.tool.AgentToolProvider;
import org.example.ai.tool.KafkaToolMode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 通过真实 AgentRunner 检查结构化 JSON 转换结果及失败时的会话记忆边界。 */
class StructuredOutputContractTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    /** 合法 JSON 必须完整映射为报告对象，并且成功结果包含可展示的报告。 */
    @Test
    void validJsonIsConvertedIntoTypedDiagnosisReport() throws Exception {
        ChatMemory memory = mock(ChatMemory.class);
        when(memory.get("structured-valid")).thenReturn(List.of());
        TestFixture fixture = fixture(json(validPayload()), memory);
        AgentRunResult result = fixture.runner().run("排查服务", "structured-valid", true);

        assertEquals(AgentRunResult.RunStatus.COMPLETED, result.status());
        assertEquals(DiagnosisStatus.DIAGNOSED, result.report().status());
        assertEquals("服务连接超时", result.report().summary());
        assertEquals("getServiceStatus", result.report().facts().get(0).source());
        assertEquals(0.8, result.report().hypotheses().get(0).confidence(), 0.000001);
        assertEquals("连接池无可用连接", result.report().hypotheses().get(0).evidence().get(0));
        assertFalse(result.report().nextActions().get(0).requiresApproval());
        assertTrue(result.answer().contains("服务连接超时"));
        verify(memory, times(2)).add(eq("structured-valid"), any(Message.class));
        verify(fixture.model(), times(1)).call(any(Prompt.class));
    }

    /** 合法 JSON 中的特殊文字应原样保留，不经身份清理器改写。 */
    @Test
    void validJsonPreservesThinkTextLiterally() throws Exception {
        Map<String, Object> payload = validPayload();
        payload.put("summary", "<think>这是用户要求逐字保留的诊断内容</think>");
        AgentRunResult result = fixture(json(payload), mock(ChatMemory.class))
                .runner().run("检查文字", "structured-think", false);

        assertEquals("<think>这是用户要求逐字保留的诊断内容</think>", result.report().summary());
        assertTrue(result.answer().contains("<think>这是用户要求逐字保留的诊断内容</think>"));
    }

    /** 缺失字段、类型强制转换、null 嵌套项和未知格式必须失败且不重试、不保存 Memory。 */
    @Test
    void invalidJsonShapesFailOnceWithoutSavingMemory() throws Exception {
        List<String> invalidJson = invalidPayloads();
        for (int index = 0; index < invalidJson.size(); index++) {
            String conversationId = "structured-invalid-" + index;
            ChatMemory memory = mock(ChatMemory.class);
            when(memory.get(conversationId)).thenReturn(List.of());
            TestFixture fixture = fixture(invalidJson.get(index), memory);

            AgentRunResult result = fixture.runner().run("排查服务", conversationId, true);

            assertEquals(AgentRunResult.RunStatus.FAILED, result.status(),
                    "无效响应序号 " + index + " 不应标记为成功");
            assertEquals(DiagnosisStatus.FAILED, result.report().status());
            verify(memory, never()).add(eq(conversationId), any(Message.class));
            verify(fixture.model(), times(1)).call(any(Prompt.class));
        }
    }

    /** 模型显式返回 FAILED 报告时也必须拒绝成功状态并避免写入会话记忆。 */
    @Test
    void explicitFailedReportDoesNotWriteMemory() throws Exception {
        Map<String, Object> payload = validPayload();
        payload.put("status", "FAILED");
        ChatMemory memory = mock(ChatMemory.class);
        when(memory.get("structured-failed-report")).thenReturn(List.of());
        TestFixture fixture = fixture(json(payload), memory);

        AgentRunResult result = fixture.runner().run("排查服务", "structured-failed-report", true);

        assertEquals(AgentRunResult.RunStatus.FAILED, result.status());
        assertEquals(DiagnosisStatus.FAILED, result.report().status());
        verify(memory, never()).add(eq("structured-failed-report"), any(Message.class));
        verify(fixture.model(), times(1)).call(any(Prompt.class));
    }

    /** 结构合法的证据不足报告是有效响应，仍应保留为 COMPLETED 结果。 */
    @Test
    void insufficientEvidenceReportIsStillACompletedResponse() throws Exception {
        Map<String, Object> payload = validPayload();
        payload.put("status", "INSUFFICIENT_EVIDENCE");
        payload.put("facts", List.of());
        payload.put("hypotheses", List.of());
        payload.put("nextActions", List.of());
        payload.put("missingInformation", List.of("需要服务调用记录"));

        AgentRunResult result = fixture(json(payload), mock(ChatMemory.class))
                .runner().run("排查未知问题", "structured-insufficient", false);

        assertEquals(AgentRunResult.RunStatus.COMPLETED, result.status());
        assertEquals(DiagnosisStatus.INSUFFICIENT_EVIDENCE, result.report().status());
        assertEquals(List.of("需要服务调用记录"), result.report().missingInformation());
    }

    /**
     * 创建严格契约下无效的 JSON 样例，覆盖根字段、嵌套字段和 JSON 文法边界。
     *
     * @return 缺失字段、字段类型不符或 JSON 文法无效的响应列表
     * @throws Exception 当测试样例映射成 JSON 失败时抛出
     */
    private List<String> invalidPayloads() throws Exception {
        List<String> values = new ArrayList<>();
        for (String field : List.of("status", "summary", "facts", "hypotheses", "nextActions",
                "missingInformation")) {
            Map<String, Object> payload = validPayload();
            payload.remove(field);
            values.add(json(payload));
        }
        values.add(json(withoutNestedField("facts", "source")));
        values.add(json(withoutNestedField("facts", "statement")));
        values.add(json(withoutNestedField("hypotheses", "cause")));
        values.add(json(withoutNestedField("hypotheses", "confidence")));
        values.add(json(withoutNestedField("hypotheses", "evidence")));
        values.add(json(withoutNestedField("nextActions", "description")));
        values.add(json(withoutNestedField("nextActions", "requiresApproval")));

        Map<String, Object> nullConfidence = validPayload();
        nested(nullConfidence, "hypotheses").put("confidence", null);
        values.add(json(nullConfidence));
        Map<String, Object> stringConfidence = validPayload();
        nested(stringConfidence, "hypotheses").put("confidence", "0.8");
        values.add(json(stringConfidence));
        Map<String, Object> stringApproval = validPayload();
        nested(stringApproval, "nextActions").put("requiresApproval", "false");
        values.add(json(stringApproval));

        Map<String, Object> unknownEnum = validPayload();
        unknownEnum.put("status", "UNRECOGNIZED");
        values.add(json(unknownEnum));
        Map<String, Object> unknownField = validPayload();
        unknownField.put("extra", "不属于结构化契约");
        values.add(json(unknownField));
        values.add(json(validPayload()).replace("\"status\":\"DIAGNOSED\"",
                "\"status\":\"DIAGNOSED\",\"status\":\"FAILED\""));
        values.add("null");
        values.add(json(validPayload()).replace("\"facts\":[", "\"facts\":[null,"));
        values.add(json(validPayload()).replace("\"hypotheses\":[", "\"hypotheses\":[null,"));
        values.add(json(validPayload()).replace("\"nextActions\":[", "\"nextActions\":[null,"));
        values.add(json(validPayload()) + " {} ");
        values.add("```json\n" + json(validPayload()) + "\n```");
        return List.copyOf(values);
    }

    /**
     * 复制一个有效报告并从指定嵌套对象中移除字段。
     *
     * @param arrayName 包含目标字段的报告数组名称
     * @param fieldName 应删除的嵌套字段名称
     * @return 缺少目标嵌套字段的报告映射
     */
    private Map<String, Object> withoutNestedField(String arrayName, String fieldName) {
        Map<String, Object> payload = validPayload();
        nested(payload, arrayName).remove(fieldName);
        return payload;
    }

    /**
     * 读取指定数组的首个可变对象，用于生成字段缺失或类型错误的样例。
     *
     * @param payload 报告根字段映射
     * @param arrayName 需要修改的嵌套数组名称
     * @return 数组中的首个嵌套 JSON 对象映射
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> nested(Map<String, Object> payload, String arrayName) {
        List<Map<String, Object>> entries = (List<Map<String, Object>>) payload.get(arrayName);
        return entries.get(0);
    }

    /**
     * 构造完整合法报告，确保单测输入与公开 JSON 字段名一致。
     *
     * @return 可被严格转换器接受的完整报告字段映射
     */
    private Map<String, Object> validPayload() {
        return object(
                "status", "DIAGNOSED",
                "summary", "服务连接超时",
                "facts", new ArrayList<>(List.of(object("statement", "调用发生超时",
                        "source", "getServiceStatus"))),
                "hypotheses", new ArrayList<>(List.of(object("cause", "数据库不可达",
                        "confidence", 0.8, "evidence", new ArrayList<>(List.of("连接池无可用连接"))))),
                "nextActions", new ArrayList<>(List.of(object("description", "检查数据库连通性",
                        "requiresApproval", false))),
                "missingInformation", new ArrayList<>());
    }

    /**
     * 按 JSON 键值对创建有序对象，便于构造稳定、可读的测试模型响应。
     *
     * @param keyValues 按键和值交错排列的键值参数
     * @return 保持插入顺序的 JSON 对象映射
     */
    private Map<String, Object> object(Object... keyValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            values.put((String) keyValues[index], keyValues[index + 1]);
        }
        return values;
    }

    /**
     * 将 Java 测试对象序列化成模型返回的严格 JSON。
     *
     * @param value 待序列化的报告对象映射
     * @return 严格 JSON 文本
     * @throws Exception 当 JSON 序列化失败时抛出
     */
    private String json(Object value) throws Exception {
        return mapper.writeValueAsString(value);
    }

    /**
     * 创建本地固定模型响应和真实 AgentRunner，不访问外部网络。
     *
     * @param json 模型固定返回的 JSON 正文
     * @param memory 本次 Agent 执行使用的会话记忆桩
     * @return 包含真实 AgentRunner 和模型桩的测试夹具
     */
    private TestFixture fixture(String json, ChatMemory memory) {
        AgentToolProvider provider = mock(AgentToolProvider.class);
        when(provider.getToolCallbacks(KafkaToolMode.LOCAL)).thenReturn(new ToolCallback[0]);
        when(provider.getToolCallbacks(KafkaToolMode.MCP)).thenReturn(new ToolCallback[0]);
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(OllamaChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(json)))));
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        when(retriever.retrieve(anyString())).thenReturn(List.of());
        AgentRunner runner = new AgentRunner(ToolCallingManager.builder().build(), provider, model,
                new ExecutionPolicy(), retriever, memory);
        return new TestFixture(runner, model);
    }

    /** 一对固定的执行引擎与模型桩，供断言调用次数。
     *
     * @param runner 被测 Agent 执行引擎
     * @param model 固定模型响应桩
     */
    private record TestFixture(AgentRunner runner, ChatModel model) { }
}
