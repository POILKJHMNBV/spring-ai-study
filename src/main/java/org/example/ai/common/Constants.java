package org.example.ai.common;

/**
 * 全局常量，集中管理跨模块共享的固定值。
 *
 * <p>采用 interface 而非 class，避免实例化；所有字段隐式为 {@code public static final}。</p>
 */
public interface Constants {

    /**
     * Agent 请求总数指标名。每次 Agent 请求结束后增加一次，并以 {@code status} 标签区分
     * 有限的结束状态；用于统计请求量，不携带会话、问题或工具参数等高基数数据。
     */
    String METRIC_AGENT_REQUESTS = "agent.requests";
    /**
     * Agent 失败请求数指标名。状态不是 {@code COMPLETED} 的请求结束时增加一次，
     * 并保留相同的 {@code status} 状态标签口径。
     */
    String METRIC_AGENT_FAILURES = "agent.failures";
    /**
     * Agent 策略拒绝次数指标名。仅当 Agent 结束状态为 {@code POLICY_REJECTED} 时增加一次。
     */
    String METRIC_AGENT_POLICY_REJECTS = "agent.policy.rejects";
    /**
     * Agent 请求耗时指标名。记录从进入外围请求观测到请求结束的纳秒时长，
     * 同时发布直方图及 P50、P95 分位数。
     */
    String METRIC_AGENT_LATENCY = "agent.latency";
    /**
     * Agent 每次请求完成的循环步骤数分布指标名。记录本次执行已完成的步骤数量，
     * 请求未产生结果时按调用方提供的零步数记录。
     */
    String METRIC_AGENT_STEPS = "agent.steps";
    /**
     * LLM 单次请求耗时指标名。记录模型调用耗时的纳秒值，并发布直方图及 P50、P95 分位数。
     */
    String METRIC_LLM_REQUEST_LATENCY = "llm.request.latency";
    /**
     * LLM Token 用量未知次数指标名。模型调用缺少可用 Usage（Usage 为 null 或 EmptyUsage）时增加一次。
     */
    String METRIC_LLM_USAGE_UNKNOWN = "llm.usage.unknown";
    /**
     * LLM 输入 Token 数分布指标名。仅在模型响应提供非空且非 EmptyUsage 的用量信息时，
     * 记录该响应的 prompt token 数值样本。
     */
    String METRIC_LLM_PROMPT_TOKENS = "llm.prompt.tokens";
    /**
     * LLM 输出 Token 数分布指标名。仅在模型响应提供非空且非 EmptyUsage 的用量信息时，
     * 记录该响应的 completion token 数值样本。
     */
    String METRIC_LLM_COMPLETION_TOKENS = "llm.completion.tokens";
    /**
     * Agent 工具调用总数指标名。每次受防护包装器完成一次完整工具调用（含重试）后增加一次。
     */
    String METRIC_AGENT_TOOL_CALLS = "agent.tool.calls";
    /**
     * 工具调用最终结果计数指标名。每次完整工具调用计数一次，并以 {@code status} 标签标记
     * 该次调用的最终成功或失败结果。
     */
    String METRIC_TOOL_CALLS = "tool.calls";
    /**
     * 工具最终失败次数指标名。每次完整工具调用以失败结果结束时增加一次；一次调用中的重试
     * 不会分别计入该指标。
     */
    String METRIC_TOOL_FAILURES = "tool.failures";
    /**
     * 工具完整调用耗时指标名。覆盖工具调用及其重试等待的总纳秒时长，发布直方图以及 P50、P95 分位数。
     */
    String METRIC_TOOL_LATENCY = "tool.latency";
    /**
     * MCP 远程工具调用结果计数指标名。仅针对标记为 MCP 的远程工具，每次完整调用计数一次，
     * 并以 {@code status} 标签标记最终结果。
     */
    String METRIC_MCP_CALLS = "mcp.calls";
    /**
     * MCP 失败次数指标名。MCP 发现阶段失败或 MCP 运行阶段调用失败时各按实际发生次数计数，
     * 并通过 {@code phase} 标签区分 {@code discovery} 与 {@code runtime}。
     */
    String METRIC_MCP_FAILURE = "mcp.failure";
    /**
     * MCP 远程工具调用耗时指标名。传给 Timer 的耗时值为纳秒，单位转换及导出由指标注册表负责，
     * 并发布直方图以及 P50、P95 分位数。
     */
    String METRIC_MCP_LATENCY = "mcp.latency";
    /**
     * 工具超时次数指标名。每次工具尝试因等待超时而被分类为 TIMEOUT 时增加一次，
     * 因此重试场景按发生超时的尝试次数计数。
     */
    String METRIC_TOOL_TIMEOUT = "tool.timeout";
    /**
     * 工具重试次数指标名。仅在再次提交工具任务成功后增加一次；失败路径或中断的退避等待
     * 不会额外计为一次重试。
     */
    String METRIC_TOOL_RETRY = "tool.retry";
    /**
     * RAG 检索耗时指标名。记录一次检索操作的纳秒耗时；成功和异常退出均记录，
     * 并发布直方图以及 P50、P95 分位数。
     */
    String METRIC_RAG_RETRIEVE_LATENCY = "rag.retrieve.latency";
    /**
     * RAG 候选文档数量分布指标名。每次检索成功时记录进入后续选择前的候选 Chunk 数值样本。
     */
    String METRIC_RAG_CANDIDATE_COUNT = "rag.candidate.count";
    /**
     * RAG 最终文档数量分布指标名。每次检索成功时记录实际选中并提供给 Agent 的 Chunk 数值样本。
     */
    String METRIC_RAG_FINAL_COUNT = "rag.final.count";

    /** Agent、Tool 与 MCP 结果计数指标中表示有限结果状态的标签键。 */
    String TELEMETRY_TAG_STATUS = "status";
    /** MCP 失败指标中表示失败阶段的标签键；其值只允许使用本文件列出的 MCP 阶段常量。 */
    String TELEMETRY_TAG_PHASE = "phase";

    /** Agent 请求指标中表示正常完成的有限状态标签值。 */
    String TELEMETRY_AGENT_STATUS_COMPLETED = "COMPLETED";
    /** Agent 请求指标中表示执行失败的有限状态标签值，也作为未知状态的安全归一化结果。 */
    String TELEMETRY_AGENT_STATUS_FAILED = "FAILED";
    /** Agent 请求指标中表示执行策略拒绝的有限状态标签值。 */
    String TELEMETRY_AGENT_STATUS_POLICY_REJECTED = "POLICY_REJECTED";
    /** Agent 请求指标中表示达到循环步数上限的有限状态标签值。 */
    String TELEMETRY_AGENT_STATUS_STEP_LIMIT_EXCEEDED = "STEP_LIMIT_EXCEEDED";
    /** 工具观测 outcome 中表示完整工具调用成功的有限状态值；不用于业务 Trace 的逐次尝试结果文本。 */
    String TELEMETRY_TOOL_OUTCOME_SUCCESS = "SUCCESS";
    /** 工具观测 outcome 中表示完整工具调用失败的有限状态值；不用于业务 Trace 的逐次尝试结果文本。 */
    String TELEMETRY_TOOL_OUTCOME_FAILED = "FAILED";
    /** MCP 失败指标中表示工具发现阶段失败的有限标签值。 */
    String TELEMETRY_MCP_FAILURE_PHASE_DISCOVERY = "discovery";
    /** MCP 失败指标中表示远程工具运行阶段失败的有限标签值。 */
    String TELEMETRY_MCP_FAILURE_PHASE_RUNTIME = "runtime";

    /** Agent 外层 Observation 出错时使用的固定错误标识，不包含异常消息或请求数据。 */
    String OBSERVATION_ERROR_AGENT_FAILED = "AGENT_FAILED";
    /** 工具发现 Observation 出错时使用的固定错误标识，不包含底层异常消息。 */
    String OBSERVATION_ERROR_DISCOVERY_FAILED = "DISCOVERY_FAILED";
    /** RAG 检索 Observation 出错时使用的固定错误标识，不包含问题或检索异常消息。 */
    String OBSERVATION_ERROR_RETRIEVAL_FAILED = "RETRIEVAL_FAILED";
    /** LLM 请求 Observation 出错时使用的固定错误标识，不包含模型异常消息或响应内容。 */
    String OBSERVATION_ERROR_MODEL_FAILED = "MODEL_FAILED";
    /** 完整工具调用 Observation 出错时使用的固定错误标识，不包含工具输入、输出或异常消息。 */
    String OBSERVATION_ERROR_TOOL_FAILED = "TOOL_FAILED";
    /** MCP 远程调用 Observation 出错时使用的固定错误标识，不包含远程异常消息。 */
    String OBSERVATION_ERROR_MCP_FAILED = "MCP_FAILED";
    /** 工具尝试任务无法提交时使用的固定 Observation 错误标识。 */
    String OBSERVATION_ERROR_SUBMISSION_FAILED = "SUBMISSION_FAILED";
    /** 调用方等待工具结果时被中断所用的固定 Observation 错误标识；业务 Trace 的中断状态保留原口径。 */
    String OBSERVATION_ERROR_INTERRUPTED = "INTERRUPTED";

    /** Micrometer 耗时 Timer 的 0.50 分位数配置值，对应 P50（中位数）；作为独立编译期常量供计时器复用。 */
    double TELEMETRY_PERCENTILE_P50 = 0.5;
    /** Micrometer 耗时 Timer 的 0.95 分位数配置值，对应 P95；作为独立编译期常量供计时器复用。 */
    double TELEMETRY_PERCENTILE_P95 = 0.95;

    /** Agent 请求外层 Observation 名称，界定单次 Agent 执行的父观测范围。 */
    String OBSERVATION_AGENT_REQUEST = "agent.request";
    /** Agent 最终响应 Observation 名称，记录请求进入最终响应阶段的观测节点。 */
    String OBSERVATION_AGENT_FINAL = "agent.final";
    /** 工具发现 Observation 名称，包围本次执行获取可用工具回调的过程。 */
    String OBSERVATION_TOOL_DISCOVERY = "tool.discovery";
    /** RAG 检索 Observation 名称，包围 Agent 请求中的知识检索过程。 */
    String OBSERVATION_RAG_RETRIEVE = "rag.retrieve";
    /** LLM 请求 Observation 名称，标记一次模型决策调用。 */
    String OBSERVATION_LLM_REQUEST = "llm.request";
    /** 完整工具调用 Observation 名称，包围一次逻辑工具调用及其重试过程。 */
    String OBSERVATION_TOOL_CALL = "tool.call";
    /** 单次工具提交 Observation 名称，标记重试循环中的一次具体执行尝试。 */
    String OBSERVATION_TOOL_ATTEMPT = "tool.attempt";
    /** MCP 远程调用 Observation 名称，标记 MCP 工具在工作线程中的远程执行区间。 */
    String OBSERVATION_MCP_CALL = "mcp.call";

    /**
     * LLM 系统提示词：定义故障排查助手的角色、可用工具和行为规范。
     *
     * <p>核心规则：
     * <ul>
     *   <li>工具结果（Tool Result）是实时事实的唯一来源，禁止编造数据</li>
     *   <li>知识库（Knowledge）只提供通用排查指南，不代表当前环境一定发生该故障</li>
     *   <li>先收集事实，再结合知识判断根因；每轮必须重新查询工具，历史数值不能作为实时证据</li>
     *   <li>引用知识时必须指定实际提供的 source，未检索到知识时明确说明</li>
     *   <li>指代消解必须有明确先行实体，禁止猜测</li>
     *   <li>最终结论必须区分已观察事实、根因判断、知识依据、待验证项和下一步建议</li>
     * </ul>
     * </p>
     */
    String SYSTEM_PROMPT = """
        你是一名 Java 生产故障排查助手。

        你可以使用：
        1. Tool：获取当前环境中的真实运行数据；
        2. Knowledge：团队故障排查知识库。

        规则：

        1. Tool Result 才能作为当前系统状态的实时事实。

        2. Knowledge 只表示通用排查知识，
           不代表当前环境一定发生了该故障。

        3. 先收集事实，再结合知识判断根因。
           完整故障检查需要同时收集服务指标、Kafka 状态、依赖指标和错误日志；
           用户只询问某项指标时，只查询对应工具。每轮涉及当前指标时必须重新查询，
           Memory 仅用于解析实体，历史回答里的数值不能作为本轮实时证据。

        4. 禁止编造 CPU、Kafka Lag、日志、
           数据库指标等实时数据。
           数值优先直接引用工具原始值。比较生产与消费速率时直接列出两者，
           不要心算、估算或取整成百分比，以免把计算误差写成事实。
           Tool Result 中 status=ERROR 或 dataAvailable=false 表示数据不可用，
           绝不代表零值、健康、无日志或无异常；必须说明缺失证据与待验证项。
           工具重试由 Harness 控制，不要为了绕过失败而重复相同调用。
           证据不可用时明确写明“证据不足，无法确定”，并列出后续补查项。

        5. 引用知识库时，只允许引用当前 Context
           中实际提供的 source。

        6. 如果没有检索到相关知识，
           必须明确说明“未检索到相关知识条目”，
           禁止虚构文档来源。

        7. 对“这个服务”“刚才那个 Topic”“它”等指代，
           只有当前 Context 或 Memory 中存在明确先行实体时才能解析。
           如果无法唯一确定所指对象，必须要求用户明确，
           禁止根据示例、常识或概率猜测实体；澄清时不要给出具体服务名示例。
                    
        8. 排查 DB 慢或 RPC 超时必须调用 getDependencyStatus，并结合
           getServiceStatus 的 CPU/线程池和 getKafkaStatus 的生产/消费速率与 Lag，
           明确列出跨组件证据链。日志只提供线索，不能单独确认 DB/RPC 根因。
           缺少对应依赖实时指标时，只能提出待验证假设，禁止直接宣布根因；
           指标正常也不能仅凭旧日志断言故障。指标相关性不等于因果关系。

        9. 最终结论必须继续区分：
           - 已观察事实
           - 根因判断/假设
           - 知识依据
           - 待验证项
           - 下一步建议
           回答保持简洁，避免重复表格或长篇知识复述，必须提供非空的结论。
           无异常时明确写“未发现明显异常”，不必逐一罗列假想故障名称。
        """;
}
