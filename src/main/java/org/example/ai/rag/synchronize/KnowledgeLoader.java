package org.example.ai.rag.synchronize;

import lombok.extern.slf4j.Slf4j;
import org.example.ai.rag.MarkdownSectionSplitter;
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

    /**
     * 启动时全量同步知识索引：扫描 → 切分 → 向量化 → 写入 manifest。
     *
     * <p>整体采用"先清后写"策略：第一步就删除旧 manifest，确保中途失败不会遗留"假成功"记录；
     * 最终全部成功后才写入新 manifest，作为本轮索引完成的唯一凭据。</p>
     *
     * <p>流程概览：
     * <ol>
     *   <li>安全守卫 — 拒绝 truncate 配置，避免 embedding 模型静默丢弃超长输入</li>
     *   <li>模型指纹 — 向本地 Ollama 查询 embedding 模型的 digest，锁定模型版本</li>
     *   <li>文档切分 — 按 Markdown 标题拆分小节，再按 token 预算二分，生成确定性 ID</li>
     *   <li>增量同步 — 委托 {@link PgKnowledgeIndex#synchronize} 在事务内逐块比较，
     *       未变化的块复用旧向量，新增或变化的块重新调用 embedding，已废弃的旧块删除</li>
     *   <li>审计清单 — 将模型身份、语料哈希、同步统计等写入 JSON manifest</li>
     * </ol>
     * </p>
     */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        // ── 第 0 步：安全守卫 ──────────────────────────────────────────────
        // 失败启动不得留下上一次成功清单，让诊断人员误认为本轮索引已完成。
        // 这是"先清后写"策略的起点：只有全部成功才会写入新 manifest。
        Files.deleteIfExists(manifestPath);
        if (truncate) {
            throw new IllegalStateException("知识索引禁止静默截断，请配置 embedding.truncate=false");
        }
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // ── 第 1 步：锁定模型版本 ──────────────────────────────────────────
        // 向本地 Ollama 查询当前 embedding 模型的 digest（内容哈希），
        // 后续参与索引指纹计算；模型升级后 digest 变化，自动触发全量重新向量化。
        String digest = modelDigest();

        // ── 第 2 步：扫描并切分知识文档 ────────────────────────────────────
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:knowledge/*.md");
        if (resources.length == 0) {
            throw new IllegalStateException("未找到知识文档");
        }

        // hashes：文件名 → 内容 SHA-256，用于 manifest 审计和 chunk 版本标记
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
            // put 返回非 null 说明文件名重复；同一文件名出现两次意味着语料配置有误。
            if (hashes.put(source, MarkdownSectionSplitter.sha256(content)) != null) {
                throw new IllegalStateException("知识来源重复: " + source);
            }
            List<Document> chunks = splitter.split(content, source);
            if (chunks.isEmpty()) {
                throw new IllegalStateException("知识文档没有可索引正文: " + source);
            }
            // 为每个 chunk 补充业务元数据，使其在检索时能被正确分类和过滤。
            for (Document chunk : chunks) {
                LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(chunk.getMetadata());
                metadata.put("category", metadata.get("domain"));
                // 当前知识文档是跨服务通用指南，不能冒充某个服务的实时证据。
                metadata.put("service", "shared");
                // version 绑定文件内容哈希：文件内容变化 → 哈希变化 → 下游可感知版本差异。
                metadata.put("version", hashes.get(source));
                documents.add(Document.builder().id(chunk.getId()).text(chunk.getText())
                        .metadata(metadata).build());
            }
        }

        // ── 第 3 步：计算索引指纹并增量同步 ────────────────────────────────
        // 指纹综合了模型身份、维度、切分参数、元数据版本等所有影响向量质量的因子。
        // 任一因子变化都会使指纹变化，进而令 PgKnowledgeIndex 判定所有块需要重新向量化。
        String fingerprint = MarkdownSectionSplitter.sha256(model + "\n" + digest + "\n"
                + index.dimensions() + "\n" + MarkdownSectionSplitter.VERSION + "\n"
                + MarkdownSectionSplitter.TOKEN_BUDGET + "\ntruncate=false\nmetadata-v1");
        // synchronize 在单个事务内完成：获取咨询锁 → 读旧块 → 逐块比较 → 写入/复用/删除。
        PgKnowledgeIndex.Result result = index.synchronize(documents, fingerprint);
        stopWatch.stop();

        // ── 第 4 步：写入审计清单 ──────────────────────────────────────────
        // manifest 是本轮索引完成的唯一凭据，记录模型身份、语料快照和同步统计，
        // 供运行时诊断和回归测试使用。只有执行到这里才写入，保证"有文件 = 成功"。
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("model", model);
        manifest.put("digest", digest);
        manifest.put("dimensions", index.dimensions());
        manifest.put("indexFingerprint", fingerprint);
        manifest.put("sourceHashes", hashes);
        // corpusHash 是所有文件哈希的再哈希，用于快速判断整个语料是否发生变化。
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
        // 归一化为 Ollama 的 "name:tag" 格式，与 /api/tags 返回的 name 字段对齐。
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
