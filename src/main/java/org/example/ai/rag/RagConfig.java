package org.example.ai.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * RAG 模块的 Spring 配置入口。
 *
 * <p>职责：
 * <ul>
 *   <li>启用 {@link RetrievalProperties} 配置绑定，将 {@code rag.retrieval.*} 配置项注入 Spring 上下文</li>
 *   <li>不自行定义 {@link VectorStore} Bean：生产环境使用 PgVectorStore，由官方 starter 自动配置</li>
 * </ul>
 * </p>
 *
 * <p>历史说明：早期使用 {@link SimpleVectorStore}（内存向量库），后来迁移到 PgVectorStore 持久化。
 * 注释掉的 Bean 保留作为参考，避免与自动配置的 vectorStore Bean 重名冲突。</p>
 */
@Configuration
@EnableConfigurationProperties(RetrievalProperties.class)
public class RagConfig {
    // PgVectorStore 由官方 starter 自动配置，避免与其 vectorStore Bean 重名。
//    @Bean
//    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
//        return SimpleVectorStore.builder(embeddingModel).build();
//    }
}
