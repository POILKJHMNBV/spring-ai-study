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
