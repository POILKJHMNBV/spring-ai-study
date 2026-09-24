package org.example.ai.config;

import org.example.ai.common.Constants;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 通用 Spring AI 配置：定义 ChatClient 和 ChatMemory Bean。
 *
 * <p>配置内容：
 * <ul>
 *   <li>{@link ChatClient} — 封装 Ollama 模型调用，默认配置系统提示词、日志切面和记忆切面</li>
 *   <li>{@link ChatMemory} — 基于内存的会话记忆存储，使用滑动窗口策略</li>
 * </ul>
 * </p>
 *
 * <p>设计要点：
 * <ul>
 *   <li>ChatClient 使用 {@link SimpleLoggerAdvisor} 记录每次 LLM 调用的输入输出</li>
 *   <li>ChatClient 使用 {@link MessageChatMemoryAdvisor} 自动加载/保存会话历史</li>
 *   <li>ChatMemory 使用 {@link InMemoryChatMemoryRepository}，重启后记忆丢失</li>
 * </ul>
 * </p>
 */
@Configuration
public class CommonConfiguration {

    /**
     * 创建 ChatClient：封装 Ollama 模型调用，配置默认系统提示词和切面。
     *
     * @param ollamaChatModel Ollama 聊天模型
     * @param chatMemory      会话记忆存储
     * @return 配置好的 ChatClient
     */
    @Bean
    public ChatClient chatClient(OllamaChatModel ollamaChatModel, ChatMemory chatMemory) {
        // 会话日志切面：记录每次 LLM 调用的输入输出。
        SimpleLoggerAdvisor simpleLoggerAdvisor = new SimpleLoggerAdvisor();
        // 会话记忆切面：自动加载历史消息并在调用后保存新消息。
        MessageChatMemoryAdvisor chatMemoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();

        return ChatClient
                .builder(ollamaChatModel)
                .defaultSystem(Constants.SYSTEM_PROMPT)        // 默认系统提示词
                .defaultAdvisors(simpleLoggerAdvisor, chatMemoryAdvisor)
                .build();
    }

    /**
     * 创建 ChatMemory：基于内存的会话记忆存储。
     *
     * <p>使用 {@link MessageWindowChatMemory} 滑动窗口策略，
     * 默认保留最近的消息，超出窗口的消息会被丢弃。</p>
     *
     * @return 配置好的 ChatMemory
     */
    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .build();
    }
}
