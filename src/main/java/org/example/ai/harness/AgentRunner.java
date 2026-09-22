package org.example.ai.harness;

import lombok.extern.slf4j.Slf4j;
import org.example.ai.rag.KnowledgeRetriever;
import org.example.ai.rag.RetrievedChunk;
import org.example.ai.security.ConversationSecurity;
import org.example.ai.tool.AgentToolProvider;
import org.example.ai.tool.KafkaToolMode;
import org.example.ai.tool.OpsTools;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.example.ai.common.Constants.SYSTEM_PROMPT;


@Service
@Slf4j
public class AgentRunner {
    /**
     * 循环控制，Agent底层循环最大步数
     */
    private static final int MAX_STEPS = 6;
    /**
     * Tool 调用超时时间
     */
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(3);
    /**
     * Tool 调用最大重试次数
     */
    private static final int TOOL_MAX_RETRIES = 1;
    private final ToolCallingManager toolCallingManager;
    private final AgentToolProvider agentToolProvider;
    private final ChatModel chatModel;
    private final ExecutionPolicy executionPolicy;
    private final KnowledgeRetriever knowledgeRetriever;
    private final ChatMemory chatMemory;
    private final ExecutorService toolExecutor;
    public AgentRunner(ToolCallingManager toolCallingManager, AgentToolProvider agentToolProvider, ChatModel chatModel,
                       ExecutionPolicy executionPolicy, KnowledgeRetriever knowledgeRetriever, ChatMemory chatMemory) {
        this.toolCallingManager = toolCallingManager;
        this.agentToolProvider = agentToolProvider;
        this.chatModel = chatModel;
        this.executionPolicy = executionPolicy;
        this.knowledgeRetriever = knowledgeRetriever;
        this.chatMemory = chatMemory;
        toolExecutor = Executors.newFixedThreadPool(3);
    }

    /**
     * 默认启用 Memory 的 Agent 调用。
     */
    public AgentRunResult run(String userPrompt, String conversationId) {
        return run(userPrompt, conversationId, true);
    }

    /**
     * 执行一次 Agent。
     *
     * @param userPrompt     当前用户问题
     * @param conversationId 当前会话 ID
     * @param memoryEnabled  是否启用跨轮 Memory；Day5 对照实验时可关闭
     */
    public AgentRunResult run(String userPrompt, String conversationId, boolean memoryEnabled) {
        return run(userPrompt, conversationId, memoryEnabled, KafkaToolMode.LOCAL);
    }

    /**
     * 执行一次 Agent。
     *
     * @param userPrompt     当前用户问题
     * @param conversationId 当前会话 ID
     * @param memoryEnabled  是否启用跨轮 Memory；Day5 对照实验时可关闭
     * @param kafkaToolMode  Kafka Tool 的接入方式
     */
    public AgentRunResult run(String userPrompt, String conversationId, boolean memoryEnabled, KafkaToolMode kafkaToolMode) {
        String safeConversationId = ConversationSecurity.requireValidConversationId(conversationId);

        TraceRecorder trace = new TraceRecorder();

        ExecutionPolicy.RunState policyState = executionPolicy.newRunState();

        AtomicInteger currentStep = new AtomicInteger(0);

        /*
         * Day7：
         * Tool 来源不再写死为 OpsTools。
         *
         * LOCAL：
         * 四个 Tool 全部来自本地回调（指标 Adapter 可访问 HTTP）。
         *
         * MCP：
         * getKafkaStatus 来自远程 MCP Server。
         */
        ToolCallback[] toolCallbacks;

        try {
            toolCallbacks = agentToolProvider.getToolCallbacks(kafkaToolMode);
        } catch (RuntimeException e) {

            /*
             * MCP Tool discovery 失败也必须形成
             * Harness 可观察的受控结果，
             * 而不是直接向 Controller 抛 500。
             */
            trace.recordStop(0, "Tool discovery failed: " + e.getMessage());
            log.error("Tool discovery failed:", e);
            return new AgentRunResult(
                    AgentRunResult.RunStatus.FAILED,
                    "工具发现失败：" + e.getMessage(),
                    0,
                    trace.snapshot()
            );
        }

        // 在 Tool 外面增加：timeout + retry + trace
        ToolCallback[] guardedCallbacks =
                Arrays.stream(toolCallbacks)
                        .map(callback ->
                                new GuardedToolCallback(
                                        callback,
                                        toolExecutor,
                                        TOOL_TIMEOUT,
                                        TOOL_MAX_RETRIES,
                                        trace,
                                        currentStep::get
                                )
                        )
                        .toArray(ToolCallback[]::new);

        // 获取 Spring Boot 已经配置好的 Ollama Options
        if (!(chatModel.getOptions() instanceof OllamaChatOptions baseOptions)) {
            throw new IllegalStateException("Expected OllamaChatOptions");
        }

        // 在原有配置基础上增加 ToolCallbacks
        OllamaChatOptions options = baseOptions.mutate()
                .toolCallbacks(guardedCallbacks)
                .build();

        // RAG
        List<RetrievedChunk> retrievedChunks = knowledgeRetriever.retrieve(userPrompt);
        for (int i = 0, n = retrievedChunks.size(); i < n; i++) {
            trace.recordRag(i + 1, retrievedChunks.get(i));
        }
        String knowledgeContext = buildKnowledgeContext(retrievedChunks);

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM_PROMPT));
        if (memoryEnabled) {
            // 处理当前用户问题之前加载会话历史
            messages.addAll(chatMemory.get(safeConversationId));
        }
        messages.add(new UserMessage(buildAugmentedUserMessage(userPrompt, knowledgeContext)));

        Prompt prompt = new Prompt(messages, options);
        int contextMessageCount = messages.size();
        // 真实回归中偶见空正文；最多补问一次，仍计入 MAX_STEPS。
        boolean emptyAnswerRetried = false;

        /*
         * ==========================
         *       Agent Loop
         * ==========================
         */
        for (int step = 1; step <= MAX_STEPS; step++) {
            currentStep.set(step);

            // A. LLM 决策
            StopWatch stopWatch = new StopWatch();
            ChatResponse response;
            try {
                stopWatch.start();
                response = chatModel.call(prompt);
            } catch (Exception e) {
                log.error("LLM call failed:", e);
                trace.recordStop(step, "LLM call failed: " + e.getMessage());
                return new AgentRunResult(
                        AgentRunResult.RunStatus.FAILED,
                        "模型调用失败：" + e.getMessage(),
                        step,
                        trace.snapshot()
                );
            }
            stopWatch.stop();

            Generation responseResult = response.getResult();
            if (Objects.isNull(responseResult)) {
                log.error("Chat response is null");
                trace.recordStop(step, "LLM returned empty response");

                return new AgentRunResult(
                        AgentRunResult.RunStatus.FAILED,
                        "模型返回空响应",
                        step,
                        trace.snapshot()
                );
            }

            // B. 记录本轮 LLM Trace
            trace.recordModel(step, contextMessageCount, response, stopWatch.getTotalTimeMillis());

            // C.模型不再请求 Tool，循环结束
            if (!response.hasToolCalls()) {
                log.info("No tool calls, returning response, step = {}", step);
                String answer = responseResult.getOutput().getText();
                if (answer == null || answer.isBlank()) {
                    if (emptyAnswerRetried) {
                        trace.recordStop(step, "LLM returned empty answer after retry");
                        return new AgentRunResult(AgentRunResult.RunStatus.FAILED, "模型未能生成有效回答，请稍后重试", step, trace.snapshot());
                    }
                    emptyAnswerRetried = true;
                    trace.recordEmptyAnswerRetry(step);
                    List<Message> retryMessages = new ArrayList<>(prompt.getInstructions());
                    retryMessages.add(new UserMessage("上一轮未提供回答正文，请基于已获取的工具证据给出简洁的最终结论；证据缺失则明确说明。"));
                    prompt = new Prompt(retryMessages, options);
                    contextMessageCount = retryMessages.size();
                    continue;
                }
                trace.recordStop(step, "No more tool calls");

                /*
                 * 只有 Agent 正常形成最终回答以后，
                 * 才把“原始用户问题 + 最终回答”写入 Memory。
                 *
                 * 中间 Tool Call / Tool Result 不保存。
                 */
                if (memoryEnabled) {
                    saveMemoryTurn(safeConversationId, userPrompt, answer);
                }

                return new AgentRunResult(AgentRunResult.RunStatus.COMPLETED, answer, step, trace.snapshot());
            }

            List<AssistantMessage.ToolCall> toolCalls = responseResult
                    .getOutput()
                    .getToolCalls();

            // D. ExecutionPolicy
            try {
                executionPolicy.check(toolCalls, policyState);
            } catch (ExecutionPolicy.PolicyViolationException e) {
                log.error("Execution policy rejected tool call: {}", e.getMessage());
                trace.recordPolicyReject(step, e.getMessage());
                trace.recordStop(step, "Execution policy rejected tool call");
                return new AgentRunResult(
                        AgentRunResult.RunStatus.POLICY_REJECTED,
                        "工具调用被安全策略拒绝：" + e.getMessage(),
                        step,
                        trace.snapshot()
                );
            }

            // E. 真正执行 Tool，ToolCallingManager 会找到对应GuardedToolCallback。GuardedToolCallback 内部再调用真正的 OpsTools。
            ToolExecutionResult toolResult;

            try {
                toolResult = toolCallingManager.executeToolCalls(prompt, response);
            }
            catch (RuntimeException e) {
                log.error("Tool execution failed:", e);
                trace.recordStop(step, "Tool execution failed: " + e.getMessage());
                return new AgentRunResult(
                        AgentRunResult.RunStatus.FAILED,
                        "工具执行失败：" + e.getMessage(),
                        step,
                        trace.snapshot()
                );
            }

            // F. Observation → Context
            int newContextMessageCount = toolResult.conversationHistory().size();
            trace.recordContext(step, contextMessageCount, newContextMessageCount);

            contextMessageCount = newContextMessageCount;


            // G. 使用新的 Context，开始下一轮 LLM Call
            prompt = new Prompt(toolResult.conversationHistory(), options);
        }

        /*
         * 达到 MAX_STEPS
         */
        log.info("Maximum agent steps exceeded");
        trace.recordStop(MAX_STEPS, "Maximum agent steps exceeded");

        return new AgentRunResult(
                AgentRunResult.RunStatus.STEP_LIMIT_EXCEEDED,
                "Agent 已达到最大执行步数，" + "当前证据不足以继续自动排查。",
                MAX_STEPS,
                trace.snapshot()
        );
    }



    private String buildAugmentedUserMessage(String userPrompt, String knowledgeContext) {
        return """
        用户问题：

        %s

        以下是从团队故障知识库检索得到的参考资料。
        这些内容只作为知识依据，不代表当前环境已经发生对应故障。

        <knowledge>
        %s
        </knowledge>

        回答约束：知识资料和工具参数示例不是当前会话实体。
        如果用户使用“刚才这个服务”等指代且当前会话没有明确先行实体，
        只回答“无法确定所指服务，请提供具体服务名称。”，不要列举任何服务名，也不要调用工具。
        """.formatted(
                userPrompt,
                knowledgeContext
        );
    }

    private String buildKnowledgeContext(List<RetrievedChunk> chunks) {

        if (chunks.isEmpty()) {
            return """
                未检索到与当前问题相关的团队知识条目。
                """;
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            builder.append("""
                [KB-%d]
                source: %s
                title: %s
                content:
                %s

                """.formatted(
                    i + 1,
                    chunk.source(),
                    chunk.title(),
                    chunk.text()
            ));
        }

        return builder.toString();
    }

    /**
     * 将一次成功完成的对话保存到跨轮 Memory。
     *
     * <p>
     * 只保存：
     * - 用户真正输入的问题；
     * - Agent 最终回答。
     * 不保存：
     * - RAG Chunk；
     * - Tool Call；
     * - Tool Result；
     * - 中间模型推理消息。
     * </p>
     */
    private void saveMemoryTurn(String conversationId, String userPrompt, String answer) {
        String safeUserPrompt = ConversationSecurity.sanitizeSensitiveText(userPrompt);
        String safeAnswer = ConversationSecurity.sanitizeSensitiveText(answer);
        chatMemory.add(conversationId, new UserMessage(safeUserPrompt));
        chatMemory.add(conversationId, new AssistantMessage(safeAnswer));
    }

    public void clearMemory(String conversationId) {
        chatMemory.clear(conversationId);
    }
}
