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

    public List<Document> split(String content, String source) {
        String domain = domain(source);
        String title = content.lines()
                .filter(line -> line.startsWith("# "))
                .map(line -> line.substring(2).strip())
                .findFirst()
                .orElse(source);
        List<Document> result = new ArrayList<>();
        for (Section section : sections(content)) {
            String prefix = "# " + title + "\n\n## " + section.heading() + "\n\n";
            // 为标题路径预留预算；切分器计数仅用于分块，模型端仍以 truncate=false 兜底。
            if (estimator.estimate(prefix) >= TOKEN_BUDGET - 8) {
                throw new IllegalArgumentException("标题过长，无法保留正文预算: " + source + "/" + section.heading());
            }
            for (String body : splitBody(prefix, section.body())) {
                String text = (prefix + body).strip();
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

    private List<String> splitBody(String prefix, String body) {
        List<String> parts = new ArrayList<>();
        String remaining = body.strip();
        while (!remaining.isEmpty()) {
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
                } else high = middle - 1;
            }
            if (fit == 0) {
                throw new IllegalArgumentException("正文无法放入切分预算");
            }
            // 优先在后半段的句末或换行结束，避免无意义的极小块。
            int end = fit;
            for (int i = fit - 1; i >= fit / 2; i--) {
                if ("。！？；\n".indexOf(points[i]) >= 0) {
                    end = i + 1;
                    break;
                }
            }
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
            remaining = new String(points, end, points.length - end).strip();
        }
        return parts;
    }

    private List<Section> sections(String content) {
        List<Section> sections = new ArrayList<>();
        String heading = "概述";
        StringBuilder body = new StringBuilder();
        boolean inFence = false;
        for (String line : content.split("\\R")) {
            // 代码围栏内的 #/## 属于示例内容，不能误当成 Markdown 标题。
            if (line.stripLeading().startsWith("```")) {
                inFence = !inFence;
            }
            if (!inFence && line.startsWith("# ")) {
                continue;
            }
            if (!inFence && line.startsWith("## ")) {
                if (!body.toString().isBlank()) {
                    sections.add(new Section(heading, body.toString().strip()));
                }
                heading = line.substring(3).strip();
                body = new StringBuilder();
            } else {
                body.append(line).append('\n');
            }
        }
        if (!body.toString().isBlank()) {
            sections.add(new Section(heading, body.toString().strip()));
        }
        return sections;
    }

    public static String domain(String source) {
        // 显式维护来源归属；新文档未登记时失败，避免默默污染某个领域。
        return switch (source) {
            case "thread-pool-exhaustion.md" -> "thread-pool";
            case "kafka-consumer-lag.md" -> "kafka";
            case "mysql-slow-query.md" -> "mysql";
            default -> throw new IllegalArgumentException("未登记的知识来源: " + source);
        };
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 不支持 SHA-256", exception);
        }
    }

    private record Section(String heading, String body) {
    }
}
