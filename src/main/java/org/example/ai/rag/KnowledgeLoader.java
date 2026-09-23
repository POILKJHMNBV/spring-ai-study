package org.example.ai.rag;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * 加载知识库数据存入向量数据库
 */
@Slf4j
@Component
@NullMarked
public class KnowledgeLoader implements ApplicationRunner {
    private final PgKnowledgeIndex index;
    private final MarkdownSectionSplitter splitter;
    private final String model;
    private final String baseUrl;
    private final boolean truncate;
    private final Path manifestPath;

    public KnowledgeLoader(PgKnowledgeIndex index,
                           MarkdownSectionSplitter splitter,
                           @Value("${spring.ai.ollama.embedding.model}") String model,
                           @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl,
                           @Value("${spring.ai.ollama.embedding.truncate:false}") boolean truncate,
                           @Value("${rag.index.manifest-path:target/rag-index/manifest.json}") String manifestPath) {
        this.index = index;
        this.splitter = splitter;
        this.model = model;
        this.baseUrl = baseUrl;
        this.truncate = truncate;
        this.manifestPath = Path.of(manifestPath);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 失败启动不得留下上一次成功清单，让诊断人员误认为本轮索引已完成。
        Files.deleteIfExists(manifestPath);
        if (truncate) {
            throw new IllegalStateException("知识索引禁止静默截断，请配置 embedding.truncate=false");
        }
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        String digest = modelDigest();
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:knowledge/*.md");
        if (resources.length == 0) {
            throw new IllegalStateException("未找到知识文档");
        }

        TreeMap<String, String> hashes = new TreeMap<>();
        List<Document> documents = new ArrayList<>();
        for (Resource resource : resources) {
            String source = resource.getFilename();
            if (Objects.isNull(source) || source.isBlank()) {
                throw new IllegalStateException("知识来源为空: " + resource);
            }
            String content;
            try (InputStream stream = resource.getInputStream()) {
                content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (hashes.put(source, MarkdownSectionSplitter.sha256(content)) != null) {
                throw new IllegalStateException("知识来源重复: " + source);
            }
            List<Document> chunks = splitter.split(content, source);
            if (chunks.isEmpty()) {
                throw new IllegalStateException("知识文档没有可索引正文: " + source);
            }
            for (Document chunk : chunks) {
                LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(chunk.getMetadata());
                metadata.put("category", metadata.get("domain"));
                // 当前知识文档是跨服务通用指南，不能冒充某个服务的实时证据。
                metadata.put("service", "shared");
                metadata.put("version", hashes.get(source));
                documents.add(Document.builder().id(chunk.getId()).text(chunk.getText())
                        .metadata(metadata).build());
            }
        }
        String fingerprint = MarkdownSectionSplitter.sha256(model + "\n" + digest + "\n"
                + index.dimensions() + "\n" + MarkdownSectionSplitter.VERSION + "\n"
                + MarkdownSectionSplitter.TOKEN_BUDGET + "\ntruncate=false\nmetadata-v1");
        PgKnowledgeIndex.Result result = index.synchronize(documents, fingerprint);
        stopWatch.stop();

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("model", model);
        manifest.put("digest", digest);
        manifest.put("dimensions", index.dimensions());
        manifest.put("indexFingerprint", fingerprint);
        manifest.put("sourceHashes", hashes);
        manifest.put("corpusHash", MarkdownSectionSplitter.sha256(hashes.toString()));
        manifest.put("splitterVersion", MarkdownSectionSplitter.VERSION);
        manifest.put("tokenBudget", MarkdownSectionSplitter.TOKEN_BUDGET);
        manifest.put("tokenEstimator", "JTokkitTokenCountEstimator; not model tokenizer");
        manifest.put("truncate", false);
        manifest.put("files", resources.length);
        manifest.put("chunks", result.chunks());
        manifest.put("embedded", result.embedded());
        manifest.put("reused", result.reused());
        manifest.put("deleted", result.deleted());
        manifest.put("builtAt", Instant.now().toString());
        manifest.put("elapsedMs", stopWatch.getTotalTimeMillis());
        Files.createDirectories(manifestPath.toAbsolutePath().getParent());
        Files.writeString(manifestPath, JsonMapper.builder().build().writerWithDefaultPrettyPrinter()
                .writeValueAsString(manifest), StandardCharsets.UTF_8);
        log.info("Knowledge index synchronized: {}", manifest);
    }

    private String modelDigest() {
        JsonNode tags = RestClient.create(baseUrl).get().uri("/api/tags").retrieve().body(JsonNode.class);
        String canonical = model.contains(":") ? model : model + ":latest";
        if (Objects.nonNull(tags)) {
            for (JsonNode item : tags.path("models")) {
                if (canonical.equals(item.path("name").asString())) {
                    String digest = item.path("digest").asString();
                    if (!digest.isBlank()) {
                        return digest;
                    }
                }
            }
        }
        throw new IllegalStateException("本机未安装模型或无法确认 digest: " + canonical);
    }
}
