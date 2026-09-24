package org.example.ai.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Component
public class MarkdownSectionSplitter {
    public static final String VERSION = "markdown-heading-budget-v2";
    public static final int TOKEN_BUDGET = 220;
    private final JTokkitTokenCountEstimator estimator = new JTokkitTokenCountEstimator();

    /**
     * 将一篇 Markdown 知识文档切分为多个可索引的 {@link Document} 块。
     *
     * <p>切分策略：先按 {@code ##} 标题拆成小节，再对每个小节按 token 预算二分，
     * 确保每个块都携带完整的标题路径（{@code # 标题 / ## 小节}），使检索时 LLM
     * 能看到上下文而不仅是孤立的正文片段。</p>
     *
     * <p>文档 ID 由 {@code source + heading + text} 的 SHA-256 生成，内容不变则 ID 不变，
     * 保证 {@link org.example.ai.rag.synchronize.PgKnowledgeIndex} 能正确复用旧向量。</p>
     *
     * @param content Markdown 全文
     * @param source  文件名，用于领域归属和来源标记
     */
    public List<Document> split(String content, String source) {
        // 将文件名映射为领域标签（thread-pool / kafka / mysql），未登记的文件名会直接失败。
        String domain = domain(source);
        // 提取一级标题作为文档标题；若缺失则回退到文件名，保证每个块都有可读标题。
        String title = content.lines()
                .filter(line -> line.startsWith("# "))
                .map(line -> line.substring(2).strip())
                .findFirst()
                .orElse(source);
        List<Document> result = new ArrayList<>();
        for (Section section : sections(content)) {
            // 标题路径作为前缀拼入每个块，让 LLM 在检索时能看到"这篇文档叫什么 / 这一节叫什么"。
            String prefix = "# " + title + "\n\n## " + section.heading() + "\n\n";
            // 为标题路径预留预算；切分器计数仅用于分块，模型端仍以 truncate=false 兜底。
            if (estimator.estimate(prefix) >= TOKEN_BUDGET - 8) {
                throw new IllegalArgumentException("标题过长，无法保留正文预算: " + source + "/" + section.heading());
            }
            for (String body : splitBody(prefix, section.body())) {
                String text = (prefix + body).strip();
                // contentHash 用于检索后校验内容完整性；id 由来源+标题+正文共同决定，内容不变则 id 不变。
                String hash = sha256(text);
                String id = sha256(source + "\n" + section.heading() + "\n" + text);
                result.add(Document.builder().id(id).text(text).metadata(Map.of(
                        "source", source, "title", title, "section", section.heading(),
                        "domain", domain, "chunkIndex", result.size(), "contentHash", hash,
                        "splitterVersion", VERSION)).build());
            }
        }
        return result;
    }

    /**
     * 将一个小节的正文按 token 预算拆成多段，每段与标题前缀拼合后不超过 {@link #TOKEN_BUDGET}。
     *
     * <p>拆分策略：先用二分法在 Unicode 码点级别找到最大可容纳位置，再向前回扫寻找
     * 句末标点（。！？；\n），优先在语义边界截断，避免把一个完整句子切成两半。
     * 若剩余正文仍然超长，循环继续拆分，直到全部处理完毕。</p>
     *
     * <p>按码点而非 char 拆分是为了避免截断 UTF-16 代理对（如 emoji），
     * 保证输出始终是合法的 Unicode 字符串。</p>
     *
     * @param prefix 标题前缀（{@code # 标题 / ## 小节}），占用部分预算，正文只能使用剩余空间
     * @param body   小节正文，可能被拆成多段
     * @return 每段正文文本（不含前缀），调用方负责拼接
     */
    private List<String> splitBody(String prefix, String body) {
        List<String> parts = new ArrayList<>();
        String remaining = body.strip();
        while (!remaining.isEmpty()) {
            // 快速路径：剩余正文加上前缀仍不超预算，整段收入，无需再拆。
            if (estimator.estimate(prefix + remaining) <= TOKEN_BUDGET) {
                parts.add(remaining);
                break;
            }
            // 按 Unicode 码点二分，防止截断代理对；最终再检查完整前缀+正文的预算。
            int[] points = remaining.codePoints().toArray();
            int low = 1, high = points.length, fit = 0;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                String candidate = new String(points, 0, middle);
                if (estimator.estimate(prefix + candidate) <= TOKEN_BUDGET) {
                    fit = middle;
                    low = middle + 1;
                } else {
                    high = middle - 1;
                }
            }
            if (fit == 0) {
                // 即使一个码点也放不下，说明前缀本身已占满预算，属于标题过长的防御性检查。
                throw new IllegalArgumentException("正文无法放入切分预算");
            }
            // 优先在后半段的句末或换行结束，避免无意义的极小块。
            // 从 fit 向前回扫到 fit/2，寻找句末标点作为更自然的截断位置。
            int end = fit;
            for (int i = fit - 1; i >= fit / 2; i--) {
                if ("。！？；\n".indexOf(points[i]) >= 0) {
                    end = i + 1;
                    break;
                }
            }
            // 句末标点可能恰好超出预算，逐码点回退直到满足约束。
            String part = new String(points, 0, end).strip();
            while (estimator.estimate(prefix + part) > TOKEN_BUDGET && end > 1) {
                part = new String(points, 0, --end).strip();
            }
            if (estimator.estimate(prefix + part) > TOKEN_BUDGET) {
                throw new IllegalArgumentException("完整块超过切分预算");
            }
            if (!part.isEmpty()) {
                parts.add(part);
            }
            // 剩余部分继续循环拆分，直到全部处理完毕。
            remaining = new String(points, end, points.length - end).strip();
        }
        return parts;
    }

    /**
     * 将 Markdown 全文按 {@code ##} 标题拆成多个 {@link Section}。
     *
     * <p>解析规则：
     * <ul>
     *   <li>{@code # } 一级标题视为文档标题，由 {@link #split} 单独处理，此处跳过</li>
     *   <li>{@code ## } 二级标题开启新小节；其后的正文归入当前小节，直到下一个 {@code ## }</li>
     *   <li>代码围栏（{@code ```}）内的 {@code #}/{@code ##} 属于示例内容，不当作标题</li>
     *   <li>第一个 {@code ## } 之前的正文归入默认小节“概述”</li>
     * </ul>
     * </p>
     */
    private List<Section> sections(String content) {
        List<Section> sections = new ArrayList<>();
        // 默认小节标题：处理第一个 ## 之前可能存在的正文。
        String heading = "概述";
        StringBuilder body = new StringBuilder();
        boolean inFence = false;
        for (String line : content.split("\\R")) {
            // 代码围栏内的 #/## 属于示例内容，不能误当成 Markdown 标题。
            if (line.stripLeading().startsWith("```")) {
                inFence = !inFence;
            }
            // 一级标题是文档标题，已在 split() 中提取，这里直接跳过。
            if (!inFence && line.startsWith("# ")) {
                continue;
            }
            if (!inFence && line.startsWith("## ")) {
                // 遇到新的二级标题：先把之前累积的正文收成一个 Section，再开启新小节。
                if (!body.toString().isBlank()) {
                    sections.add(new Section(heading, body.toString().strip()));
                }
                heading = line.substring(3).strip();
                body = new StringBuilder();
            } else {
                body.append(line).append('\n');
            }
        }
        // 文件末尾没有新的 ## 来触发收尾，手动将最后一个小节收入列表。
        if (!body.toString().isBlank()) {
            sections.add(new Section(heading, body.toString().strip()));
        }
        return sections;
    }

    /**
     * 将文件名映射为领域标签，用于检索时的分类和过滤。
     *
     * <p>采用显式映射而非自动推断：新增知识文档必须在此登记归属，
     * 否则直接失败，避免未分类文档默默污染某个领域的检索结果。</p>
     */
    public static String domain(String source) {
        // 显式维护来源归属；新文档未登记时失败，避免默默污染某个领域。
        return switch (source) {
            case "thread-pool-exhaustion.md" -> "thread-pool";
            case "kafka-consumer-lag.md" -> "kafka";
            case "mysql-slow-query.md" -> "mysql";
            default -> throw new IllegalArgumentException("未登记的知识来源: " + source);
        };
    }

    /**
     * 计算文本的 SHA-256 摘要，返回十六进制字符串。
     *
     * <p>在本类中用于两个场景：
     * <ul>
     *   <li>{@code contentHash} — 块内容的哈希，供检索后校验完整性</li>
     *   <li>文档 ID — 由 {@code source + heading + text} 共同计算，内容不变则 ID 不变，
     *       保证 {@link org.example.ai.rag.synchronize.PgKnowledgeIndex} 能正确复用旧向量</li>
     * </ul>
     * </p>
     */
    public static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 不支持 SHA-256", exception);
        }
    }

    /** 一个 Markdown 小节的解析结果：标题和正文，由 {@link #sections} 产出、由 {@link #split} 消费。 */
    private record Section(String heading, String body) {
    }
}
