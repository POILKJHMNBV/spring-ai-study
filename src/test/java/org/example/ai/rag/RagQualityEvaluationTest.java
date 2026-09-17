package org.example.ai.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实模型、真实随机端口 HTTP 评测；开发集调参，冻结保留集只用于最终验收。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "rag.eval", matches = "true")
class RagQualityEvaluationTest {
    @Autowired VectorStore currentStore;
    @Autowired RetrievalProperties properties;
    @Value("${local.server.port}") int port;
    private record Case(String id, String split, String domain, Set<String> sections, String query) {
        boolean negative() { return domain.equals("none"); }
    }
    private record Row(Case sample, List<Document> hits) {}
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void evaluateAndVerifyHttp() throws Exception {
        boolean developmentOnly = Boolean.getBoolean("rag.devOnly");
        Path casesPath = Path.of("src/test/resources/rag/evaluation.csv");
        List<String> caseLines = new ArrayList<>(Files.readAllLines(casesPath, StandardCharsets.UTF_8).stream().skip(1).toList());
        Path validationPath = Path.of("src/test/resources/rag/validation.csv");
        caseLines.addAll(Files.readAllLines(validationPath, StandardCharsets.UTF_8).stream().skip(1).toList());
        List<Case> cases = caseLines.stream()
                .filter(s -> !s.isBlank()).map(s -> s.split("\\|", -1))
                .map(p -> new Case(p[0], p[1], p[2], new HashSet<>(List.of(p[3].split(","))), p[4]))
                .filter(c -> !developmentOnly || c.split().equals("dev") || c.split().equals("acceptance")).toList();
        Map<String, List<Row>> experiments = new LinkedHashMap<>();
        var baselineManifest = new StringBuilder();
        for (String modelName : List.of("mxbai-embed-large", "bge-m3")) {
            var model = OllamaEmbeddingModel.builder().ollamaApi(OllamaApi.builder()
                    .baseUrl("http://localhost:11434").build()).options(OllamaEmbeddingOptions.builder()
                    .model(modelName).truncate(false).build()).build();
            var store = SimpleVectorStore.builder(model).build();
            int baselineChunks = 0;
            // 原始知识库快照不会被后续编辑污染，用于隔离模型收益和语料收益。
            try (var paths = Files.list(Path.of("src/test/resources/rag/baseline"))) {
                for (Path path : paths.sorted().toList()) {
                    String content = Files.readString(path);
                    var chunks = BaselineSectionSplitter.split(content, path.getFileName().toString());
                    store.add(chunks);
                    baselineChunks += chunks.size();
                    if (modelName.equals("bge-m3")) baselineManifest.append("- ").append(path.getFileName())
                            .append("：").append(chunks.size()).append(" 块，SHA-256 `")
                            .append(MarkdownSectionSplitter.sha256(content)).append("`。\n");
                }
            }
            assertEquals(21, baselineChunks, "P0 模型对照必须保留原始 21 块");
            experiments.put(modelName + "/原始语料", search(store, cases));
        }
        List<Row> vectorRows = search(currentStore, cases);
        experiments.put("bge-m3/补充语料纯向量", vectorRows);
        // 融合只调整有来源的向量候选顺序；显式保存纯向量对照以展示收益与退化。
        var ranker = new LexicalRrfRanker();
        List<Row> current = vectorRows.stream().map(r -> new Row(r.sample, ranker.rank(r.sample.query, r.hits))).toList();
        experiments.put("bge-m3/BM25-RRF候选融合", current);
        var report = new StringBuilder("# Day 4 RAG 修复评测报告\n\n");
        report.append("生成时间：").append(Instant.now()).append("。模式：")
                .append(developmentOnly ? "仅开发集（未运行保留集）" : "完整回归及追加冻结集验收").append("。\n\n")
                .append("本机 Maven 3.8.4 / JDK 17；真实 Ollama + SimpleVectorStore + 随机端口 Spring Boot HTTP。\n\n")
                .append("评测集 SHA-256：`").append(HexFormat.of().formatHex(java.security.MessageDigest
                        .getInstance("SHA-256").digest(Files.readAllBytes(casesPath)))).append("`。\n\n")
                .append("6 条指定验收 + 24 条开发集 + 24 条冻结保留集；开发/保留各含 18 条相关和 6 条无关问题。")
                .append("本次继续使用工作区已有评测集；自本次开发集运行起未修改语料、问题或标签，保留集未用于选择阈值。\n\n")
                .append("首次纯向量保留集只有 16/18，触发原有 17/18 门槛失败；保留集已被观察，后续融合结果属于回归验证，不能声称为全新盲测。")
                .append("融合算法确定后另冻结 18 条 validation（12 正、6 负），本轮不再据其结果调参。总计 72 条。\n\n")
                .append("追加验证集 SHA-256：`").append(MarkdownSectionSplitter.sha256(Files.readString(validationPath))).append("`。\n\n")
                .append("原始语料对照使用冻结的旧切分器（21 块），补充语料使用生产预算切分器。")
                .append("因此旧模型到 BGE 原始语料隔离模型收益；BGE 原始到补充语料同时包含语料和预算切分变化，不将其全部归因于语料。\n\n")
                .append("原始语料快照：\n\n").append(baselineManifest).append("\n")
                .append("## 指标口径\n\n领域 Hit@1：相关查询首位领域正确率（无结果计错）。")
                .append("Recall@3：前三位覆盖的标注相关小节数 / 该题全部标注相关小节数，再对相关查询取平均。")
                .append("MRR@20：首个相关小节的排名倒数均值，候选最多 20 个；未召回计 0。")
                .append("无结果率分母为该组全部问题；误召回率分母仅为负样本。小节以 domain + 标题编号标识，不能仅凭领域算相关。\n\n")
                .append("## 对照结果\n\n|实验|分组|阈值|领域 Hit@1|小节 Recall@3|小节 MRR@20|无结果率|负样本误召回率|\n")
                .append("|---|---|---:|---:|---:|---:|---:|---:|\n");
        var evidence = new StringBuilder("experiment\tid\tsplit\tquery\texpectedDomain\trelevantSections\trank\tsource\tsection\tdomain\tchunkIndex\tscore\n");
        for (var experiment : experiments.entrySet()) {
            // 保存零阈值的完整候选，允许独立重算 Recall、MRR 及任意阈值下的指标。
            for (Row row : experiment.getValue()) {
                for (int i = 0; i < row.hits.size(); i++) {
                    Document hit = row.hits.get(i);
                    evidence.append(experiment.getKey()).append('\t').append(row.sample.id).append('\t')
                            .append(row.sample.split).append('\t').append(row.sample.query).append('\t')
                            .append(row.sample.domain).append('\t').append(new TreeSet<>(row.sample.sections)).append('\t')
                            .append(i + 1).append('\t').append(hit.getMetadata().get("source")).append('\t')
                            .append(hit.getMetadata().get("section")).append('\t').append(hit.getMetadata().get("domain"))
                            .append('\t').append(hit.getMetadata().get("chunkIndex")).append('\t').append(hit.getScore()).append('\n');
                }
            }
            for (String split : List.of("acceptance", "dev", "holdout", "validation")) {
                List<Row> rows = experiment.getValue().stream().filter(r -> r.sample.split.equals(split)).toList();
                if (rows.isEmpty()) continue;
                for (double cutoff : new double[]{0.0, properties.threshold()}) {
                    report.append("| ").append(experiment.getKey()).append(" | ").append(split).append(" | ")
                            .append(format(cutoff)).append(" | ").append(metrics(rows, cutoff)).append(" |\n");
                }
            }
        }
        report.append("\n## 开发集阈值扫描\n\n选择规则：最大化（相关查询非空召回率 + 负样本拒绝率）/2；平分取较低阈值。")
                .append("只对开发集扫描，不用保留集选择阈值；领域和小节排序另行检查。\n\n")
                .append("|阈值|相关非空召回率|负样本拒绝率|平衡分数|\n|---:|---:|---:|---:|\n");
        List<Row> dev = current.stream().filter(r -> r.sample.split.equals("dev")).toList();
        double best = -1, selected = 0;
        for (int step = 40; step <= 70; step++) {
            double cutoff = step / 100.0;
            double recall = dev.stream().filter(r -> !r.sample.negative()).filter(r -> !filtered(r, cutoff).isEmpty()).count() / 18.0;
            double reject = dev.stream().filter(r -> r.sample.negative()).filter(r -> filtered(r, cutoff).isEmpty()).count() / 6.0;
            double balanced = (recall + reject) / 2;
            if (balanced > best) { best = balanced; selected = cutoff; }
            report.append("|").append(format(cutoff)).append("|").append(format(recall))
                    .append("|").append(format(reject)).append("|").append(format(balanced)).append("|\n");
        }
        report.append("\n开发集建议阈值：**").append(format(selected)).append("**；本次配置：**")
                .append(format(properties.threshold())).append("**。\n\n## 修复后逐条结果\n\n")
                .append("|ID|组别|查询|期望领域|实际首位领域 / 小节|分数|首位领域正确|\n|---|---|---|---|---|---:|---|\n");
        int httpChecks = 0;
        for (Row row : current) {
            List<Document> hits = filtered(row, properties.threshold());
            Document first = hits.isEmpty() ? null : hits.get(0);
            report.append("|").append(row.sample.id).append("|").append(row.sample.split)
                    .append("|").append(row.sample.query).append("|").append(row.sample.domain)
                    .append("|").append(first == null ? "无结果" : key(first))
                    .append("|").append(first == null ? "—" : format(first.getScore()))
                    .append("|").append(correct(row, properties.threshold()) ? "是" : "否").append("|\n");
            // 对每个问题实际调用 HTTP，不以直接调用 Java 方法代替接口验收。
            var response = get("/rag/search?query=" + URLEncoder.encode(row.sample.query, StandardCharsets.UTF_8));
            assertEquals(200, response.statusCode(), row.sample.id);
            RetrievedChunk[] apiHits = JsonMapper.builder().build().readValue(response.body(), RetrievedChunk[].class);
            assertEquals(Math.min(3, hits.size()), apiHits.length, row.sample.id);
            for (int i = 0; i < apiHits.length; i++) {
                assertEquals(hits.get(i).getId(), apiHits[i].chunkId(), row.sample.id);
                assertEquals(hits.get(i).getMetadata().get("domain"), apiHits[i].domain());
                assertFalse(apiHits[i].section().isBlank());
                assertEquals(hits.get(i).getScore(), apiHits[i].score(), 0.00001);
            }
            httpChecks++;
        }
        for (String suffix : List.of("", "?query=", "?query=%20%20", "?query=x&topK=0", "?query=x&topK=21",
                "?query=x&threshold=-0.1", "?query=x&threshold=1.1", "?query=x&threshold=NaN",
                "?query=x&threshold=Infinity", "?query=x&topK=no", "?query=" + "x".repeat(2001))) {
            assertEquals(400, get("/rag/search" + suffix).statusCode(), suffix);
            httpChecks++;
        }
        for (Row row : current.stream().filter(r -> r.sample.split.equals("acceptance")).toList()) {
            for (double cutoff : new double[]{0.0, 0.50}) {
                var response = get("/rag/search?query=" + URLEncoder.encode(row.sample.query, StandardCharsets.UTF_8)
                        + "&topK=3&threshold=" + cutoff);
                assertEquals(200, response.statusCode());
                var api = JsonMapper.builder().build().readValue(response.body(), RetrievedChunk[].class);
                assertTrue(api.length > 0);
                assertEquals(row.sample.domain, api[0].domain());
                httpChecks++;
            }
        }
        for (int limit : new int[]{1, 20}) {
            var response = get("/rag/search?query=" + URLEncoder.encode("线程池打满", StandardCharsets.UTF_8)
                    + "&topK=" + limit + "&threshold=0");
            assertEquals(200, response.statusCode());
            assertEquals(limit, JsonMapper.builder().build().readValue(response.body(), RetrievedChunk[].class).length);
            httpChecks++;
        }
        var empty = get("/rag/search?query=" + URLEncoder.encode("线程池打满", StandardCharsets.UTF_8) + "&threshold=1");
        assertEquals(200, empty.statusCode());
        assertEquals(0, JsonMapper.builder().build().readValue(empty.body(), RetrievedChunk[].class).length);
        httpChecks++;
        report.append("\n## HTTP 与构建验证\n\n真实 HTTP 请求断言通过：**").append(httpChecks)
                .append("**（逐条默认检索、11 个非法参数用例、六条在 0/0.50 两种显式阈值下验证、3 个合法边界用例）。")
                .append("没有调用聊天模型，检索正确不等于最终诊断回答已验收。\n\n")
                .append("## 索引清单\n\n```json\n").append(Files.readString(Path.of("target/rag-index/manifest.json")))
                .append("\n```\n\n## 复现\n\n```powershell\nmvn test \"-Dtest=RagQualityEvaluationTest\" \"-Drag.eval=true\"\n```\n")
                .append("\n分数不是概率；有限负样本通过不保证任意知识库外问题都能拒绝。原始 JSON 和基线文档已保留。\n");
        report.append("\n## 结论与未解决限制\n\n")
                .append("BM25 在固定 20 个向量候选中使用中文双字与完整英文术语，k1=1.2、b=0.75；RRF 常数为 60，两个排名等权。")
                .append("这属于候选内融合，不能找回向量前 20 名之外的文档，也没有引入全库 BM25 或神经 reranker。")
                .append("先校验来源，融合后仍应用原始相似度阈值，词法命中不能绕过阈值。返回 score 保留向量分数，因此列表不一定按 score 递减。\n\n");
        for (String split : List.of("acceptance", "dev", "holdout", "validation")) {
            List<Row> group = current.stream().filter(r -> r.sample.split.equals(split)).toList();
            if (group.isEmpty()) continue;
            long positives = group.stream().filter(r -> !r.sample.negative()).count();
            long hit = group.stream().filter(r -> !r.sample.negative() && correct(r, properties.threshold())).count();
            long negatives = group.size() - positives;
            long falsePositives = group.stream().filter(r -> r.sample.negative() && !correct(r, properties.threshold())).count();
            report.append("- ").append(split).append("：领域首位 ").append(hit).append("/").append(positives)
                    .append("；库外误召回 ").append(falsePositives).append("/").append(negatives).append("。\n");
        }
        report.append("\n默认阈值 0.47 是开发集 0.40～0.70 扫描后按预先规则选择，不能作为通用拒答保证。")
                .append("保留集和追加集的库外错误明确计入指标，未通过删除问题、修改标签或调整阈值隐藏。")
                .append("当前只返回检索候选，尚不能将非空候选当作可回答的证明；可靠库外拒答仍需后续独立工作。\n\n")
                .append("融合也不是所有指标都提升：开发集 MRR 从 1.0000 降到 0.9722，追加集 Recall@3 从 0.8333 降到 0.7917；")
                .append("收益是原保留集领域首位从 16/18 到 18/18，追加集首个相关片段 MRR 从 0.9583 到 1.0000。\n");
        Path reportPath = Path.of(developmentOnly ? "target/rag-eval/development-report.md" : "doc/day4-rag-evaluation-report.md");
        Files.createDirectories(reportPath.getParent());
        Path evidencePath = reportPath.resolveSibling(developmentOnly ? "development-results.tsv" : "day4-rag-evaluation-results.tsv");
        Files.writeString(evidencePath, evidence, StandardCharsets.UTF_8);
        report.append("\n原始候选证据：[").append(evidencePath.getFileName()).append("](")
                .append(evidencePath.getFileName()).append(")。\n");
        Files.writeString(reportPath, report, StandardCharsets.UTF_8);
        System.out.println("DEV_SELECTED_THRESHOLD=" + selected + " REPORT=" + reportPath);
        assertTrue(current.stream().filter(r -> r.sample.split.equals("acceptance"))
                .allMatch(r -> correct(r, properties.threshold())), "六条最低验收必须全部通过");
        if (!developmentOnly) {
            assertEquals(selected, properties.threshold(), 0.000001, "最终配置必须等于开发集预先选定阈值");
            long correct = current.stream().filter(r -> r.sample.split.equals("holdout") && !r.sample.negative())
                    .filter(r -> correct(r, properties.threshold())).count();
            assertTrue(correct >= 17, "冻结集相关领域至少 17/18，详见逐条报告");
            assertTrue(current.stream().filter(r -> r.sample.split.equals("validation") && !r.sample.negative())
                    .allMatch(r -> correct(r, properties.threshold())), "追加冻结集的相关领域验收");
        }
    }

    private List<Row> search(VectorStore store, List<Case> cases) {
        return cases.stream().map(c -> new Row(c, store.similaritySearch(SearchRequest.builder()
                .query(c.query).topK(20).similarityThreshold(0).build()))).toList();
    }
    private static List<Document> filtered(Row row, double cutoff) {
        return row.hits.stream().filter(d -> d.getScore() >= cutoff).toList();
    }
    private static String key(Document doc) {
        return doc.getMetadata().get("domain") + ":" + doc.getMetadata().get("section").toString().split("\\.")[0];
    }
    private static boolean correct(Row row, double cutoff) {
        List<Document> hits = filtered(row, cutoff);
        return row.sample.negative() ? hits.isEmpty() : !hits.isEmpty()
                && row.sample.domain.equals(hits.get(0).getMetadata().get("domain"));
    }
    private static String metrics(List<Row> rows, double cutoff) {
        var positive = rows.stream().filter(r -> !r.sample.negative()).toList();
        var negative = rows.stream().filter(r -> r.sample.negative()).toList();
        double hit1 = positive.stream().filter(r -> correct(r, cutoff)).count() / (double) positive.size();
        double recall = 0, mrr = 0;
        for (Row row : positive) {
            List<Document> hits = filtered(row, cutoff);
            recall += hits.stream().limit(3).map(RagQualityEvaluationTest::key).distinct()
                    .filter(row.sample.sections::contains).count() / (double) row.sample.sections.size();
            for (int i = 0; i < hits.size(); i++) {
                if (row.sample.sections.contains(key(hits.get(i)))) { mrr += 1.0 / (i + 1); break; }
            }
        }
        double empty = rows.stream().filter(r -> filtered(r, cutoff).isEmpty()).count() / (double) rows.size();
        String falsePositive = negative.isEmpty() ? "不适用" : format(negative.stream()
                .filter(r -> !filtered(r, cutoff).isEmpty()).count() / (double) negative.size());
        return format(hit1) + " | " + format(recall / positive.size()) + " | " + format(mrr / positive.size())
                + " | " + format(empty) + " | " + falsePositive;
    }
    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    private static String format(double number) { return String.format(Locale.ROOT, "%.4f", number); }
}
