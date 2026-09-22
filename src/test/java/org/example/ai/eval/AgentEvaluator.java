package org.example.ai.eval;

import org.example.ai.harness.AgentRunResult;
import org.example.ai.harness.TraceRecorder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Day6 Eval 判定器。
 *
 * <p>
 * 第一版尽可能使用确定性规则：
 * 状态、Tool Trace、RAG Trace、步数、Token、
 * 超时和数值证据都来自程序实际轨迹，
 * 不再让另一个 LLM 决定“测试到底通过没有”。
 * </p>
 */
final class AgentEvaluator {

    /**
     * 提取回答中显式引用的 Markdown 知识源。
     */
    private static final Pattern KNOWLEDGE_SOURCE_PATTERN =
            Pattern.compile(
                    "(?i)([a-z0-9_.-]+\\.md)"
            );

    /**
     * 提取所有普通数字。
     */
    private static final Pattern NUMBER_PATTERN =
            Pattern.compile(
                    "-?\\d+(?:\\.\\d+)?"
            );

    /**
     * 只审计明显属于实时系统指标的数值。
     *
     * <p>
     * 普通列表编号、步骤编号、置信度等不会进入这个检查，
     * 避免把自然语言格式误认为实时指标。
     * </p>
     */
    private static final Pattern METRIC_VALUE_PATTERN =
            Pattern.compile(
                    "(?i)"
                            + "(?:"
                            + "cpu(?:percent)?"
                            + "|memory(?:percent)?"
                            + "|内存"
                            + "|lag"
                            + "|producerate"
                            + "|生产速率"
                            + "|consumerate"
                            + "|消费速率"
                            + "|consumercount"
                            + "|消费者(?:数量)?"
                            + "|threadpool(?:active|max)?"
                            + "|线程池(?:活跃线程|最大线程|active|max)?"
                            + "|p99(?:ms)?"
                            + "|timeout(?:\\s*rate)?"
                            + "|超时率"
                            + "|连接池(?:active|max)?"
                            + ")"
                            + "[^0-9A-Za-z\\r\\n-]{0,20}"
                            + "(-?\\d+(?:\\.\\d+)?)"
            );

    /**
     * 对一个 Eval Case 的 AgentRunResult 做结构化评分。
     */
    AgentEvalCaseResult evaluate(
            AgentEvalCase evalCase,
            AgentRunResult runResult,
            long latencyMs) {

        List<TraceRecorder.TraceEvent> trace =
                runResult.trace();

        String answer =
                Objects.requireNonNullElse(
                        runResult.answer(),
                        ""
                );

        Set<String> actualTools = trace.stream()
                .filter(event ->
                        event.type()
                                == TraceRecorder.EventType.TOOL)
                .map(TraceRecorder.TraceEvent::name)
                .collect(
                        Collectors.toCollection(TreeSet::new)
                );

        Set<String> ragSources = trace.stream()
                .filter(event ->
                        event.type()
                                == TraceRecorder.EventType.RAG)
                .map(TraceRecorder.TraceEvent::name)
                .collect(
                        Collectors.toCollection(TreeSet::new)
                );

        boolean mainJudgmentPass =
                containsRequiredKeywords(
                        answer,
                        evalCase.requiredKeywordGroups()
                )
                        && containsNoForbiddenKeywords(
                        answer,
                        evalCase.forbiddenAnswerKeywords()
                );

        boolean statusPass =
                runResult.status()
                        == evalCase.expectedStatus();

        boolean toolSelectionPass =
                actualTools.containsAll(
                        evalCase.requiredTools()
                )
                        && evalCase.allowedTools()
                        .containsAll(actualTools);

        boolean stepPass =
                runResult.completedSteps() <= 6;

        boolean ragExpectationPass =
                !evalCase.expectNoRagHit()
                        || ragSources.isEmpty();

        boolean timeoutObserved = trace.stream()
                .filter(event ->
                        event.type()
                                == TraceRecorder.EventType.TOOL)
                .map(TraceRecorder.TraceEvent::status)
                .filter(Objects::nonNull)
                .anyMatch(status ->
                        status.startsWith("TIMEOUT")
                );

        boolean exceptionHandlingPass =
                !evalCase.expectToolTimeout()
                        || (
                        timeoutObserved
                                && runResult.status()
                                == AgentRunResult.RunStatus.COMPLETED
                );

        Set<String> citedSources =
                extractKnowledgeSources(answer);

        /*
         * 模型只能引用本次 RAG Trace 中真实存在的 source。
         */
        boolean sourceCitationPass =
                ragSources.containsAll(citedSources);

        EvidenceAudit evidenceAudit =
                auditMetricEvidence(
                        answer,
                        trace
                );

        int promptTokens =
                sumTokens(trace, TokenType.PROMPT);

        int completionTokens =
                sumTokens(trace, TokenType.COMPLETION);

        int totalTokens =
                sumTokens(trace, TokenType.TOTAL);

        boolean overallPass =
                mainJudgmentPass
                        && statusPass
                        && toolSelectionPass
                        && stepPass
                        && ragExpectationPass
                        && exceptionHandlingPass
                        && sourceCitationPass
                        && evidenceAudit.pass();

        return new AgentEvalCaseResult(
                evalCase.id(),
                evalCase.description(),
                runResult.status(),
                mainJudgmentPass,
                statusPass,
                toolSelectionPass,
                stepPass,
                evidenceAudit.pass(),
                sourceCitationPass,
                ragExpectationPass,
                exceptionHandlingPass,
                overallPass,
                runResult.completedSteps(),
                actualTools,
                ragSources,
                evidenceAudit.unsupportedMetricValues(),
                latencyMs,
                promptTokens,
                completionTokens,
                totalTokens,
                answer
        );
    }

    /**
     * 每一组关键字只需要命中其中任意一个；
     * 不同组之间必须全部满足。
     */
    private boolean containsRequiredKeywords(
            String answer,
            List<List<String>> keywordGroups) {

        String normalized =
                answer.toLowerCase(Locale.ROOT);

        return keywordGroups.stream()
                .allMatch(group ->
                        group.stream()
                                .map(keyword ->
                                        keyword.toLowerCase(
                                                Locale.ROOT
                                        ))
                                .anyMatch(
                                        normalized::contains
                                )
                );
    }

    /**
     * 用于 E05 和 E10 等负向案例：
     * 不允许模型“自己补出”不存在的故障或上下文。
     */
    private boolean containsNoForbiddenKeywords(
            String answer,
            Set<String> forbiddenKeywords) {

        String normalized =
                answer.toLowerCase(Locale.ROOT);

        return forbiddenKeywords.stream()
                .map(keyword ->
                        keyword.toLowerCase(Locale.ROOT)
                )
                .noneMatch(normalized::contains);
    }

    /**
     * 检查回答中的实时指标是否可以在 Tool Result 中找到来源。
     *
     * <p>
     * 这里只检查 CPU、Lag、P99、线程池等明显实时数值，
     * 不检查普通编号或建议里的时间数字。
     * </p>
     */
    private EvidenceAudit auditMetricEvidence(
            String answer,
            List<TraceRecorder.TraceEvent> trace) {

        String toolOutput = trace.stream()
                .filter(event ->
                        event.type()
                                == TraceRecorder.EventType.TOOL)
                .map(TraceRecorder.TraceEvent::output)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));

        Set<String> supportedNumbers =
                extractAllNumbers(toolOutput);

        /*
         * Tool 原始值允许直接引用。
         * 再补充两个最常见、可以直接根据原始值推导出的百分比：
         * 1. 线程池 active/max；
         * 2. consumeRate/produceRate。
         */
        addRatio(
                supportedNumbers,
                findNamedNumber(
                        toolOutput,
                        "threadPoolActive"
                ),
                findNamedNumber(
                        toolOutput,
                        "threadPoolMax"
                )
        );

        addRatio(
                supportedNumbers,
                findNamedNumber(
                        toolOutput,
                        "consumeRate"
                ),
                findNamedNumber(
                        toolOutput,
                        "produceRate"
                )
        );

        // Day10：允许从独立依赖指标换算连接池占用率和 RPC 超时百分比。
        addRatio(supportedNumbers, findNamedNumber(toolOutput, "activeConnections"),
                findNamedNumber(toolOutput, "maxConnections"));
        addRatio(supportedNumbers, findNamedNumber(toolOutput, "timeoutRate"), BigDecimal.ONE);

        String normalizedAnswer =
                // 知识引用编号不是实时指标；也不允许正则跨行捕获列表序号。
                answer.replace(",", "").replaceAll("\\[KB-\\d+\\]", "");

        Matcher matcher =
                METRIC_VALUE_PATTERN.matcher(
                        normalizedAnswer
                );

        Set<String> unsupported =
                new TreeSet<>();

        while (matcher.find()) {
            String value =
                    normalizeNumber(
                            matcher.group(1)
                    );

            if (!supportedNumbers.contains(value)) {
                unsupported.add(value);
            }
        }

        return new EvidenceAudit(
                unsupported.isEmpty(),
                unsupported
        );
    }

    private Set<String> extractAllNumbers(String text) {

        Set<String> result = new HashSet<>();

        if (text == null || text.isBlank()) {
            return result;
        }

        Matcher matcher =
                NUMBER_PATTERN.matcher(
                        text.replace(",", "")
                );

        while (matcher.find()) {
            result.add(
                    normalizeNumber(
                            matcher.group()
                    )
            );
        }

        return result;
    }

    /**
     * 从 Tool JSON / Record 文本中寻找指定字段数值。
     */
    private BigDecimal findNamedNumber(
            String text,
            String fieldName) {

        Pattern pattern = Pattern.compile(
                "(?i)"
                        + Pattern.quote(fieldName)
                        + "[\"']?\\s*[:=]\\s*"
                        + "(-?\\d+(?:\\.\\d+)?)"
        );

        Matcher matcher =
                pattern.matcher(text);

        if (!matcher.find()) {
            return null;
        }

        return new BigDecimal(
                matcher.group(1)
        );
    }

    /**
     * 将常见比例推导值加入允许集合。
     */
    private void addRatio(
            Set<String> supportedNumbers,
            BigDecimal numerator,
            BigDecimal denominator) {

        if (numerator == null
                || denominator == null
                || denominator.signum() == 0) {
            return;
        }

        BigDecimal ratio = numerator
                .multiply(BigDecimal.valueOf(100))
                .divide(
                        denominator,
                        2,
                        RoundingMode.HALF_UP
                );

        supportedNumbers.add(
                normalizeNumber(
                        ratio.toPlainString()
                )
        );

        supportedNumbers.add(
                normalizeNumber(
                        ratio.setScale(
                                1,
                                RoundingMode.HALF_UP
                        ).toPlainString()
                )
        );

        supportedNumbers.add(
                normalizeNumber(
                        ratio.setScale(
                                0,
                                RoundingMode.HALF_UP
                        ).toPlainString()
                )
        );
    }

    private Set<String> extractKnowledgeSources(
            String answer) {

        Set<String> sources =
                new TreeSet<>();

        Matcher matcher =
                KNOWLEDGE_SOURCE_PATTERN.matcher(
                        answer
                );

        while (matcher.find()) {
            sources.add(matcher.group(1));
        }

        return sources;
    }

    private String normalizeNumber(String raw) {
        return new BigDecimal(raw)
                .stripTrailingZeros()
                .toPlainString();
    }

    private int sumTokens(
            List<TraceRecorder.TraceEvent> trace,
            TokenType type) {

        return trace.stream()
                .filter(event ->
                        event.type()
                                == TraceRecorder.EventType.MODEL)
                .map(event ->
                        switch (type) {
                            case PROMPT ->
                                    event.promptTokens();
                            case COMPLETION ->
                                    event.completionTokens();
                            case TOTAL ->
                                    event.totalTokens();
                        }
                )
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
    }

    private enum TokenType {
        PROMPT,
        COMPLETION,
        TOTAL
    }

    private record EvidenceAudit(
            boolean pass,
            Set<String> unsupportedMetricValues
    ) {
    }
}

/**
 * 一个案例最终保存的结构化 Eval 结果。
 */
record AgentEvalCaseResult(
        String id,
        String description,
        AgentRunResult.RunStatus status,
        boolean mainJudgmentPass,
        boolean statusPass,
        boolean toolSelectionPass,
        boolean stepPass,
        boolean evidenceFaithfulnessPass,
        boolean sourceCitationPass,
        boolean ragExpectationPass,
        boolean exceptionHandlingPass,
        boolean overallPass,
        int completedSteps,
        Set<String> actualTools,
        Set<String> ragSources,
        Set<String> unsupportedMetricValues,
        long latencyMs,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        String answer
) {
}
