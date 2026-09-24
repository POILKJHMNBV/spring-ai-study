package org.example.ai.eval;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Day12 固定查询集；参考标签只标文档 source，避免把当前分块算法当作相关性真值。 */
final class RetrievalCases {
    static final String RESOURCE = "eval/retrieval-cases.tsv";
    static final Set<String> CORPUS = Set.of("thread-pool-exhaustion.md", "kafka-consumer-lag.md",
            "mysql-slow-query.md");

    /** 纯数据读取工具，不创建实例。 */
    private RetrievalCases() {
    }

    /** 一条固定 source 标注查询；split 区分开发与验证，使用者不得根据验证结果调参。 */
    record Case(String id, String split, String kind, Set<String> expectedSources, String query) {
    }

    /** 严格读取五列 TSV，拒绝重号、重复问法、未知来源和开发/验证集污染。 */
    static List<Case> load() throws IOException {
        String raw;
        try (var stream = new ClassPathResource(RESOURCE).getInputStream()) {
            raw = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String[] lines = raw.split("\\R");
        if (lines.length == 0 || !"id\tsplit\tkind\texpectedSources\tquery".equals(lines[0])) {
            throw new IllegalArgumentException("检索集表头不符");
        }
        List<Case> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> queries = new HashSet<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            String[] fields = lines[i].split("\\t", -1);
            if (fields.length != 5 || fields[0].isBlank() || fields[4].isBlank()
                    || !(fields[1].equals("dev") || fields[1].equals("validation"))
                    || !(fields[2].equals("plan") || fields[2].equals("paraphrase")
                    || fields[2].equals("technical") || fields[2].equals("cross-component"))
                    || !ids.add(fields[0]) || !queries.add(fields[4])) {
                throw new IllegalArgumentException("无效或重复的检索样例，行 " + (i + 1));
            }
            Set<String> gold = new LinkedHashSet<>(List.of(fields[3].split(",", -1)));
            if (gold.isEmpty() || gold.contains("") || !CORPUS.containsAll(gold)) {
                throw new IllegalArgumentException("未知或空的 gold source，行 " + (i + 1));
            }
            result.add(new Case(fields[0], fields[1], fields[2], Set.copyOf(gold), fields[4]));
        }
        if (result.stream().filter(c -> c.split().equals("validation") && c.kind().equals("plan")).count() < 4
                || result.stream().noneMatch(c -> c.split().equals("dev"))
                || !result.stream().filter(c -> c.split().equals("dev"))
                .flatMap(c -> c.expectedSources().stream()).collect(java.util.stream.Collectors.toSet()).containsAll(CORPUS)
                || !result.stream().filter(c -> c.split().equals("validation"))
                .flatMap(c -> c.expectedSources().stream()).collect(java.util.stream.Collectors.toSet()).containsAll(CORPUS)
                || result.stream().noneMatch(c -> c.kind().equals("cross-component") && c.expectedSources().size() > 1)) {
            throw new IllegalArgumentException("检索集缺少计划问题、完整来源覆盖或跨组件标签");
        }
        return List.copyOf(result);
    }
}
