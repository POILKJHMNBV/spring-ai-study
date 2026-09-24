package org.example.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.example.ai.security.ConversationSecurity;
import org.example.ai.tool.OpsTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;
import reactor.core.publisher.Flux;

import java.util.Objects;

/**
 * 简单对话服务：基于 Spring AI ChatClient 的单轮对话实现。
 *
 * <p>与 {@link org.example.ai.harness.AgentRunner} 的区别：
 * <ul>
 *   <li>本服务使用 ChatClient 的声明式 API，适合简单问答场景</li>
 *   <li>AgentRunner 使用底层 ChatModel + 手动循环，适合复杂的多步推理场景</li>
 * </ul>
 * </p>
 *
 * <p>核心职责：
 * <ul>
 *   <li>会话 ID 校验：防止日志注入或路径遍历</li>
 *   <li>敏感信息脱敏：用户输入中的 Token/API Key 在发送给模型前先脱敏</li>
 *   <li>工具注册：将 {@link OpsTools} 注册为可用工具</li>
 *   <li>会话记忆：通过 Memory Advisor 自动加载历史消息</li>
 *   <li>用量统计：记录每次 LLM 调用的 Token 用量和耗时</li>
 * </ul>
 * </p>
 */
@Service
@Slf4j
public class ChatService {
    private final ChatClient chatClient;
    private final OpsTools opsTools;
    public ChatService(ChatClient chatClient, OpsTools opsTools) {
        this.chatClient = chatClient;
        this.opsTools = opsTools;
    }

    public String chat(String userPrompt, String conversationId) {
        String safeConversationId = ConversationSecurity.requireValidConversationId(conversationId);

        /*
         * 对用户输入中的显式 Token/API Key 做基础脱敏。
         * 一方面避免发送给模型，另一方面避免随后被 Memory Advisor 保存。
         */
        String safePrompt = ConversationSecurity.sanitizeSensitiveText(userPrompt);

        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        ChatResponse chatResponse = chatClient.prompt(safePrompt)
                .tools(opsTools)
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, safeConversationId))
                .call()
                .chatResponse();
        stopWatch.stop();

        if (Objects.isNull(chatResponse) || Objects.isNull(chatResponse.getResult())) {
            log.error("Chat response is null");
            return "Chat response is null";
        }
        String content = chatResponse.getResult()
                .getOutput()
                .getText();

        Usage usage = chatResponse.getMetadata().getUsage();
        log.info("""
                
                ===== LLM CALL =====
                input: {}
                output: \n{}
                elapsed: {} ms
                promptTokens: {}
                completionTokens: {}
                totalTokens: {}
                ====================
                """,
                userPrompt,
                content,
                stopWatch.getTotalTimeMillis(),
                usage.getPromptTokens(),
                usage.getCompletionTokens(),
                usage.getTotalTokens()
        );

        return content;
    }

    public Flux<String> streamChat(String userPrompt, String conversationId) {
        return null;
    }
}
