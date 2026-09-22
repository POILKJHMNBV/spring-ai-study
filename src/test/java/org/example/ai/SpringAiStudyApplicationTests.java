package org.example.ai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.example.ai.rag.KnowledgeLoader;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = "spring.ai.mcp.client.enabled=false")
class SpringAiStudyApplicationTests {

    // 启动装配测试不访问 Ollama；索引行为由 KnowledgeLoaderTest 独立验证。
    @MockitoBean
    private KnowledgeLoader knowledgeLoader;

    @Test
    void contextLoads() {
    }

}
