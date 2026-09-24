package org.example.ai.rag.rerank;

import org.example.ai.rag.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 词项覆盖重排器：基于查询词在候选中的覆盖率重新排序。
 *
 * <p>算法流程：
 * <ol>
 *   <li>提取查询词集合（英文完整词，中文相邻双字）</li>
 *   <li>对每个候选计算覆盖率分数：
 *       <ul>
 *         <li>标题 + 小节覆盖率：权重 40%</li>
 *         <li>正文最佳窗口覆盖率：权重 60%</li>
 *       </ul>
 *   </li>
 *   <li>按覆盖率降序排列，截取 topK</li>
 * </ol>
 * </p>
 *
 * <p>设计要点：
 * <ul>
 *   <li>权重在看评测结果前固定，避免过拟合</li>
 *   <li>正文按句/行切分，长段再按 160 个 Unicode 码点分窗、80 码点步长滑动，
 *       避免整篇长文堆词获益，而是关注局部最佳匹配</li>
 *   <li>覆盖率 = 命中的不同查询词数 / 不同查询词总数，是可解释的词项匹配，
 *       并非 cross encoder，也不把向量 score 当作重排分或置信概率</li>
 *   <li>Java 稳定排序保证覆盖率相同时沿用 RRF 名次</li>
 * </ul>
 * </p>
 */
@Component
public class TokenCoverageReranker implements DocumentReranker {
    // 分词正则：英文保留完整词，中文按连续汉字匹配后拆为双字。
    private static final Pattern TERMS = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*|[\\p{IsHan}]+");
    // 正文切分正则：按句末标点或换行切分，用于滑动窗口前的粗粒度切分。
    private static final Pattern PASSAGES = Pattern.compile("[。！？!?；;\\r\\n]+");
    // 滑动窗口大小（Unicode 码点数），限制单次匹配范围，避免长文堆词。
    private static final int WINDOW_CODE_POINTS = 160;
    // 滑动窗口步长，与窗口大小配合实现重叠滑动，避免遗漏边界处的关键词。
    private static final int WINDOW_STRIDE = 80;

    /**
     * 对输入建立独立结果列表；Java 稳定排序保证覆盖率相同时沿用 RRF 名次。
     *
     * <p>正文按句/行切分，长段再按 160 个 Unicode 码点分窗，避免整篇长文堆词获益。</p>
     *
     * @param query      用户问题，用于提取查询词集合
     * @param candidates 已过滤的候选列表，按 RRF 顺序传入
     * @param topK       期望返回条数，必须在 1～20 之间
     * @return 按覆盖率降序排列的候选列表，不超过 topK 个
     */
    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK) {
        if (query == null || query.isBlank() || candidates == null || topK < 1 || topK > 20) {
            throw new IllegalArgumentException("query、候选和 topK 必须有效");
        }
        Set<String> wanted = terms(query);
        List<RetrievedChunk> sorted = new ArrayList<>(candidates);
        if (!wanted.isEmpty()) {
            // 使用 IdentityHashMap：以对象引用为键，避免 RetrievedChunk 的 equals/hashCode 干扰。
            Map<RetrievedChunk, Double> scores = new IdentityHashMap<>();
            sorted.forEach(chunk -> scores.put(chunk, relevance(chunk, wanted)));
            // 稳定排序：覆盖率相同时保持传入顺序（即 RRF 顺序）。
            sorted.sort((left, right) -> Double.compare(scores.get(right), scores.get(left)));
        }
        return List.copyOf(sorted.subList(0, Math.min(topK, sorted.size())));
    }

    /**
     * 计算固定字段权重下的覆盖率。
     *
     * <p>权重分配：标题 + 小节占 40%，正文最佳窗口占 60%。
     * 权重在看评测结果前固定，避免过拟合。</p>
     *
     * @param chunk  候选块
     * @param wanted 查询词集合
     * @return 加权覆盖率分数
     */
    private double relevance(RetrievedChunk chunk, Set<String> wanted) {
        // 标题 + 小节：合并后计算覆盖率，权重 40%。
        double heading = coverage(chunk.title() + " " + chunk.section(), wanted);
        // 正文最佳窗口：按句/行切分后滑动窗口，取最高覆盖率，权重 60%。
        double bestPassage = 0;
        for (String passage : PASSAGES.split(chunk.text() == null ? "" : chunk.text())) {
            int[] points = passage.codePoints().toArray();
            // 滑动窗口：每次前进 WINDOW_STRIDE 个码点，避免遗漏边界处的关键词。
            for (int start = 0; start < points.length; start += WINDOW_STRIDE) {
                int length = Math.min(WINDOW_CODE_POINTS, points.length - start);
                bestPassage = Math.max(bestPassage,
                        coverage(new String(points, start, length), wanted));
            }
        }
        return 0.4 * heading + 0.6 * bestPassage;
    }

    /**
     * 计算单字段或单正文窗口覆盖了多少个不同的查询词。
     *
     * <p>覆盖率 = 命中的不同查询词数 / 不同查询词总数。
     * 使用不同词数而非总词频，避免同一词重复出现过度获益。</p>
     *
     * @param text   待计算文本
     * @param wanted 查询词集合
     * @return 覆盖率，范围 [0, 1]
     */
    private double coverage(String text, Set<String> wanted) {
        Set<String> matched = terms(text);
        matched.retainAll(wanted);
        return (double) matched.size() / wanted.size();
    }

    /**
     * 分词：英文保留完整词，连续中文拆为相邻双字；无词典或领域答案表。
     *
     * <p>返回 Set 而非 Map，因为覆盖率只关心词项是否出现，不关心词频。</p>
     *
     * @param text 待分词文本
     * @return 词项集合
     */
    private Set<String> terms(String text) {
        Set<String> result = new HashSet<>();
        if (text == null || text.isBlank()) return result;
        Matcher matcher = TERMS.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (Character.UnicodeScript.of(token.codePointAt(0)) == Character.UnicodeScript.HAN) {
                int[] points = token.codePoints().toArray();
                // 单字直接加入，保留完整语义。
                if (points.length == 1) result.add(token);
                // 相邻双字（bigram），保留部分语义信息。
                for (int i = 0; i + 1 < points.length; i++) {
                    result.add(new String(points, i, 2));
                }
            } else {
                result.add(token);
            }
        }
        return result;
    }
}
