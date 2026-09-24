package org.example.ai.rag.rerank;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 混合排序器：用 BM25 词法分数与向量相似度顺序通过 RRF（Reciprocal Rank Fusion）融合。
 *
 * <p>算法流程：
 * <ol>
 *   <li>对每个候选计算 BM25 词法分数（仅考虑查询词）</li>
 *   <li>按 BM25 分数降序排列，得到词法顺序</li>
 *   <li>用 RRF 融合向量顺序和词法顺序：{@code score = 1/(k + rank_vector) + 1/(k + rank_lexical)}</li>
 *   <li>按融合分数降序排列，得到最终顺序</li>
 * </ol>
 * </p>
 *
 * <p>设计要点：
 * <ul>
 *   <li>RRF_K = 60：标准 RRF 参数，防止高排名项过度主导</li>
 *   <li>只融合名次，不直接相加 BM25 与余弦分数；DTO 的 score 仍表示原始向量相似度</li>
 *   <li>中文使用相邻双字分词，英文保留完整词，避免人为维护查询关键词表</li>
 * </ul>
 * </p>
 */
@Component
public class LexicalRrfRanker {
    // 分词正则：英文保留完整词，中文按连续汉字匹配后拆为双字。
    private static final Pattern TERMS = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*|[\\p{IsHan}]+");
    // RRF 平滑参数，防止高排名项过度主导融合分数。
    private static final double RRF_K = 60;

    /**
     * 基于传入向量顺序与当前固定候选集的 BM25 词法顺序做 RRF。
     *
     * <p>空正文候选保留空词频表以维持下标对应，且不会改写 Document.score。
     * 候选数小于 2 时直接返回，无需融合。</p>
     *
     * @param query      用户问题，用于提取查询词
     * @param candidates 向量搜索返回的候选列表，按向量相似度降序
     * @return 按 RRF 融合分数降序排列的候选列表
     */
    public List<Document> rank(String query, List<Document> candidates) {
        if (candidates.size() < 2) {
            return candidates;
        }
        // 第一步：对每个候选分词，构建词频表。
        List<Map<String, Integer>> documents = candidates.stream()
                .map(d -> terms(Optional.ofNullable(d.getText()).orElse("")))
                .toList();
        Set<String> queryTerms = terms(query).keySet();
        // 第二步：计算平均文档长度，用于 BM25 长度归一化。
        double averageLength = documents.stream()
                .mapToInt(LexicalRrfRanker::length)
                .average()
                .orElse(1);
        // 第三步：统计每个词的文档频率（df），用于计算 IDF。
        Map<String, Integer> frequencies = new HashMap<>();
        documents.forEach(d -> d.keySet().forEach(t -> frequencies.merge(t, 1, Integer::sum)));
        // 第四步：对每个候选计算 BM25 分数，仅考虑查询词。
        Map<String, Double> scores = new HashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Integer> document = documents.get(i);
            double score = 0;
            for (String term : queryTerms) {
                int tf = document.getOrDefault(term, 0);
                if (tf == 0) {
                    continue;
                }
                int df = frequencies.get(term);
                // BM25 IDF 公式：log(1 + (N - df + 0.5) / (df + 0.5))。
                double idf = Math.log(1 + (candidates.size() - df + 0.5) / (df + 0.5));
                // BM25 分数公式：IDF * (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * dl / avgdl))。
                // 其中 k1 = 1.2, b = 0.25 为标准参数。
                score += idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * length(document) / averageLength));
            }
            scores.put(candidates.get(i).getId(), score);
        }
        // 第五步：按 BM25 分数降序排列，得到词法顺序。
        List<Document> lexical = candidates.stream().filter(d -> scores.get(d.getId()) > 0)
                .sorted(Comparator.<Document>comparingDouble(d -> scores.get(d.getId())).reversed()
                        .thenComparing(Document::getId)).toList();
        // 第六步：RRF 融合。向量顺序按传入下标，词法顺序按 BM25 排序结果。
        // 融合公式：score = 1/(k + rank_vector) + 1/(k + rank_lexical)。
        Map<String, Double> fused = new HashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            fused.put(candidates.get(i).getId(), 1 / (RRF_K + i + 1));
        }
        for (int i = 0; i < lexical.size(); i++) {
            fused.merge(lexical.get(i).getId(), 1 / (RRF_K + i + 1), Double::sum);
        }
        // 只融合名次，不直接相加 BM25 与余弦分数；DTO 的 score 仍表示原始向量相似度。
        return candidates.stream().sorted(Comparator.<Document>comparingDouble(d -> fused.get(d.getId())).reversed())
                .toList();
    }

    /** 计算当前候选的总词频，用于 BM25 长度归一化。 */
    private static int length(Map<String, Integer> terms) {
        return terms.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * 分词：英文保留完整词，中文以相邻双字建词频，不依赖领域关键词表。
     *
     * <p>中文使用相邻双字（bigram）而非单字，避免过度拆分导致语义丢失。
     * 例如“线程池”会拆分为“线程”和“程池”，保留部分语义信息。</p>
     *
     * @param text 待分词文本
     * @return 词项到词频的映射
     */
    private static Map<String, Integer> terms(String text) {
        if (Objects.isNull(text) || text.isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, Integer> result = new HashMap<>();
        Matcher matcher = TERMS.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (Character.UnicodeScript.of(token.codePointAt(0)) == Character.UnicodeScript.HAN) {
                // 中文使用相邻双字，英文异常类名/指标名完整保留，避免人为维护查询关键词表。
                int[] points = token.codePoints().toArray();
                for (int i = 0; i + 1 < points.length; i++) {
                    result.merge(new String(points, i, 2), 1, Integer::sum);
                }
            } else {
                result.merge(token, 1, Integer::sum);
            }
        }
        return result;
    }
}
