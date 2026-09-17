package org.example.ai.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 Ollama 检索对照实验：复用业务切分器和向量库，不启动 Spring 容器或聊天模型。
 * 默认跳过，显式使用 -Drag.eval=true 启用，避免普通单元测试依赖本机模型服务。
 * 只在内存中建索引，报告写入 target/rag-eval，不修改知识库和应用配置。
 */
@EnabledIfSystemProperty(named = "rag.eval", matches = "true")
class RagEmbeddingEvaluationTest {
    private record Query(String text, String domain, String group) {}
    private static final List<Query> QUERIES = List.of(
            new Query("线程池打满", "thread-pool", "acceptance"),
            new Query("执行器没有空闲线程", "thread-pool", "acceptance"),
            new Query("Kafka 消费积压", "kafka", "acceptance"),
            new Query("生产速度高于消费速度", "kafka", "acceptance"),
            new Query("数据库查询越来越慢", "mysql", "acceptance"),
            new Query("连接池快满了", "mysql", "acceptance"),
            // 补充未写入知识库的表达，防止仅凭六条固定查询判断泛化效果。
            new Query("工作线程都忙着，新任务只能排队", "thread-pool", "holdout"),
            new Query("执行器拒绝提交的任务", "thread-pool", "holdout"),
            new Query("线程池队列持续变长", "thread-pool", "holdout"),
            new Query("RejectedExecutionException", "thread-pool", "holdout"),
            new Query("消息进来的比处理掉的多", "kafka", "holdout"),
            new Query("消费者一直追不上生产者", "kafka", "holdout"),
            new Query("消费组的 lag 不断上涨", "kafka", "holdout"),
            new Query("分区数量会限制消费者并行度吗", "kafka", "holdout"),
            new Query("SQL 响应耗时持续上升", "mysql", "holdout"),
            new Query("HikariCP 获取连接一直等待", "mysql", "holdout"),
            new Query("数据库连接用尽，业务拿不到连接", "mysql", "holdout"),
            new Query("慢 SQL 应该如何检查索引和锁", "mysql", "holdout"),
            // 无关问题仅记录分数，用于后续阈值校准，不预设武断的统一阈值。
            new Query("今天天气怎么样", "none", "negative"),
            new Query("如何烤一块巧克力蛋糕", "none", "negative"),
            new Query("Kubernetes 证书过期如何更新", "none", "negative")
    );

    @Test
    void compareRealEmbeddingModels() throws Exception {
        Path output = Path.of("target/rag-eval");
        Files.createDirectories(output);
        var chunks = new ArrayList<Document>();
        var manifest = new StringBuilder("source\tsha256\tchunks\n");
        var chunkReport = new StringBuilder("source\tindex\tsection\tchars\tstartsWithTitle\n");
        var splitter = new MarkdownSectionSplitter();
        try (var files = Files.list(Path.of("src/main/resources/knowledge"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                byte[] bytes = Files.readAllBytes(file);
                String source = file.getFileName().toString();
                List<Document> split = splitter.split(new String(bytes, StandardCharsets.UTF_8), source);
                manifest.append(source).append('\t')
                        .append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                        .append('\t').append(split.size()).append('\n');
                for (Document chunk : split) {
                    assertNotNull(chunk.getText());
                    chunkReport.append(source).append('\t').append(chunk.getMetadata().get("chunkIndex"))
                            .append('\t').append(chunk.getMetadata().get("section"))
                            .append('\t').append(chunk.getText().length())
                            .append('\t').append(chunk.getText().startsWith("# ")).append('\n');
                }
                chunks.addAll(split);
            }
        }
        assertEquals(3, manifest.toString().lines().count() - 1, "应读取三篇原始知识文档");
        Files.writeString(output.resolve("manifest.tsv"), manifest, StandardCharsets.UTF_8);
        Files.writeString(output.resolve("chunks.tsv"), chunkReport, StandardCharsets.UTF_8);
        StringBuilder report = new StringBuilder(
                "variant\tgroup\tquery\texpected\trank\tactual\tsource\tchunk\tscore\n");
        int bgeAcceptanceHits = 0;
        // 同一原文、同一切分、同一检索库：每次换模型都新建索引，禁止混用向量空间。
        for (String modelName : List.of("mxbai-embed-large", "bge-m3")) {
            var model = OllamaEmbeddingModel.builder()
                    .ollamaApi(OllamaApi.builder().baseUrl("http://localhost:11434").build())
                    .options(OllamaEmbeddingOptions.builder().model(modelName)
                            .truncate(false).keepAlive("5m").build())
                    .build();
            var store = SimpleVectorStore.builder(model).build();
            store.add(chunks);
            // mxbai 增加 query instruction 的独立对照；文档端保持原样。
            List<String> prefixes = modelName.equals("mxbai-embed-large")
                    ? List.of("", "Represent this sentence for searching relevant passages: ") : List.of("");
            for (String prefix : prefixes) {
                String variant = modelName + (prefix.isEmpty() ? "-raw" : "-query-prefix");
                int acceptance = 0;
                int holdout = 0;
                for (Query query : QUERIES) {
                    List<Document> hits = store.similaritySearch(SearchRequest.builder()
                            .query(prefix + query.text()).topK(3).similarityThreshold(0.0).build());
                    assertFalse(hits.isEmpty(), "零阈值检索应返回候选");
                    boolean correct = domain(hits.get(0)).equals(query.domain());
                    if (correct && query.group().equals("acceptance")) acceptance++;
                    if (correct && query.group().equals("holdout")) holdout++;
                    for (int rank = 0; rank < hits.size(); rank++) {
                        Document hit = hits.get(rank);
                        report.append(variant).append('\t').append(query.group()).append('\t')
                                .append(query.text()).append('\t').append(query.domain()).append('\t')
                                .append(rank + 1).append('\t').append(domain(hit)).append('\t')
                                .append(hit.getMetadata().get("source")).append('\t')
                                .append(hit.getMetadata().get("chunkIndex")).append('\t')
                                .append(String.format(Locale.ROOT, "%.9f", hit.getScore())).append('\n');
                    }
                    // 每条查询后写报告，即使模型中途失败也能保留已完成的实验结果。
                    Files.writeString(output.resolve("results.tsv"), report, StandardCharsets.UTF_8);
                }
                System.out.printf("RAG EVAL %s acceptance=%d/6 holdout=%d/12 chunks=%d%n",
                        variant, acceptance, holdout, chunks.size());
                if (modelName.equals("bge-m3")) bgeAcceptanceHits = acceptance;
            }
        }
        // domain 仅从 source 推导用于评测，没有用于查询路由或过滤候选。
        assertEquals(6, bgeAcceptanceHits, "BGE-M3 必须满足六条首位领域验收，详见 results.tsv");
    }

    private static String domain(Document document) {
        return switch (String.valueOf(document.getMetadata().get("source"))) {
            case "thread-pool-exhaustion.md" -> "thread-pool";
            case "kafka-consumer-lag.md" -> "kafka";
            case "mysql-slow-query.md" -> "mysql";
            default -> throw new IllegalArgumentException("未知知识文档");
        };
    }
}
