# Day13：标准可观测性

## 推进判断与任务清单

Day12 无严重阻塞，可以推进 Day13。修改前使用本机 Maven 3.8.4 / Oracle JDK 17.0.11
重跑 `Day11PgVectorIT`，3 项全部通过；测试实际连接已配置的虚拟机 PostgreSQL + pgvector。

本次委派两位 GPT-6 Sol / medium 子 Agent，分别负责生产观测链路与测试评测；主 Agent
负责官方依据核验、独立代码审查、本机 Maven 编译测试和最终验收。最后再由一位相同模型及
推理配置的子 Agent 补充中断、排队取消和拒绝提交的边界回归。任务如下：

- 保留 `TraceRecorder`，接入 Micrometer Observation 和标准指标注册表。
- 串联 Agent、RAG、LLM、Tool 和 MCP 调用，验证线程池中的观测上下文传递。
- 覆盖学习计划所列指标，以及失败、超时、重试、策略拒绝和 Token 统计。
- 使用固定范围的标签，不将问题、回答、工具参数、凭据和原始日志放入指标。
- 使用确定性故障测试验证计数与链路，用真实 PGVector / BGE-M3 / Ollama 运行固定评测。
- 输出 P50/P95、平均步数、平均工具调用数、失败率、Token 和组件耗时。
- 使用 JDK 17、本机 Maven；新增与修改逻辑补充中文注释；不执行 commit 或 push。

## 官方依据

2026-09-24 实际读取以下官方页面（HTTP 200），并结合项目依赖中的官方 JAR 核对 API。
网页快照保存在本次工作区的 `target/day13-official-*.html`，不作为需要提交的产物。

- [Spring AI Observability](https://docs.spring.io/spring-ai/reference/observability/index.html)：
  模型、向量库、工具等组件的标准观测；提示词、回答、工具内容的日志默认关闭。
- [Micrometer Observation](https://docs.micrometer.io/micrometer/reference/observation/introduction.html)：
  Observation 生命周期、Scope 和 Handler；低基数标签必须具有有限的取值范围。
- [Spring Boot Metrics](https://docs.spring.io/spring-boot/reference/actuator/metrics.html)：
  MeterRegistry、Actuator metrics、分位数与直方图配置。
- [Spring Boot Tracing](https://docs.spring.io/spring-boot/reference/actuator/tracing.html)：
  Micrometer tracing 桥接、采样和跨线程/网络传播要求。
- [Spring AI 2.0.1 PGVector 自动配置公开源码](https://github.com/spring-projects/spring-ai/blob/v2.0.1/auto-configurations/vector-stores/spring-ai-autoconfigure-vector-store-pgvector/src/main/java/org/springframework/ai/vectorstore/pgvector/autoconfigure/PgVectorStoreAutoConfiguration.java)：
  自动读取 `VectorStoreObservationConvention` Bean 并传给 PGVector Builder；通过此扩展点
  移除可能携带查询正文和过滤条件的高基数字段，保留标准数据库观测。

计划中的 `agent.*`、`llm.*`、`tool.*`、`rag.*` 和 `mcp.*` 是本项目定义的业务指标，
并非声称 Spring AI 自动提供这些名字。框架自带指标另有官方名称；统计时不能把同一操作的
项目指标与框架指标相加，否则会重复计数。

## TraceRecorder 与 Micrometer 的职责

`TraceRecorder` 保存单次 Agent 的业务事件：模型步骤、工具结果、检索来源、策略拒绝与
停止原因，供解释执行过程和既有 Eval 使用。Micrometer 则提供标准计时、跨组件父子关系
以及跨请求聚合的指标，用于定位耗时、失败趋势和运行状态。两者服务的查询不同，因此保留并行使用。

完整链路不等于把原始内容写入 trace。指标和标准观测只需操作名称、状态、数量及耗时；
业务证据继续由既有受控结果通道承载。

## 指标口径与使用

| 指标 | 类型与统计口径 |
|---|---|
| `agent.requests` / `agent.failures` | 请求结束时计数；未 COMPLETED 的请求计入失败，含策略拒绝和步数上限 |
| `agent.latency` / `agent.steps` | 每次请求的总耗时 / 步数分布 |
| `agent.tool.calls` / `tool.calls` | 每次逻辑工具调用计一次，重试不重复计入逻辑调用数 |
| `agent.policy.rejects` | 安全策略拒绝次数 |
| `llm.request.latency` | 每次模型调用耗时，包含失败调用 |
| `llm.prompt.tokens` / `llm.completion.tokens` | 模型元数据中的输入 / 输出 Token 分布；以 totalAmount 取总量 |
| `llm.usage.unknown` | 未返回可用用量的调用次数，不虚构 Token 数 |
| `tool.failures` | 重试耗尽或不可恢复的逻辑调用失败次数 |
| `tool.timeout` / `tool.retry` | 实际发生的超时 / 重试次数 |
| `tool.latency` | 完整逻辑工具调用耗时，包含重试等待 |
| `rag.retrieve.latency` | 检索和排序耗时，失败同样记录 |
| `rag.candidate.count` / `rag.final.count` | 成功检索的候选数 / 最终返回数分布；失败未知数不当作零 |
| `mcp.calls` / `mcp.latency` | 远程 Kafka 工具的逻辑调用次数 / 耗时，包含重试等待 |
| `mcp.failure` | 远程调用或工具发现失败；按 phase 区分，不能直接与仅调用阶段的分母混算 |

`tool.latency` 与 `mcp.latency` 在远程 Kafka 工具上重叠，不能相加当成总耗时。
模型元数据由提供方决定；框架将部分缺失字段转换为零后，应用无法恢复原始缺失信息。
这里显式识别 `EmptyUsage`，不把缺少用量误当成真实零消耗。

通过 `/actuator/metrics` 查看指标名，`/actuator/metrics/agent.latency` 查看请求耗时统计。
仅暴露 health 与 metrics；没有添加外部遥测服务或导出凭据。OpenTelemetry bridge 在进程内
生成真实 trace/span ID；本次验收不要求部署外部追踪后端。

固定 Eval 使用每例真实耗时的最近秩法计算 P50/P95，报告保留原始毫秒值供复核。
三例评测是功能性小样本，P95 通常就是最大值，不能解释为生产负载下的延迟承诺。

## 复现命令

沿用项目已配置的虚拟机 PostgreSQL + pgvector、本机 BGE-M3 与 Ollama。新增集成测试
自行创建 UUID 隔离 schema，测试结束清理本次对象。

```powershell
mvn -DskipTests compile
mvn test
mvn test '-Dtest=Day11PgVectorIT,RetrievalEvaluationIT,Day13ObservabilityIT'

# 单独终端启动既有 MCP Server，再运行真实 MCP 传输验收
mvn -f mcp-kafka-server/pom.xml -DskipTests package
java -jar mcp-kafka-server/target/kafka-mcp-server-0.0.1-SNAPSHOT.jar
mvn test '-Dtest=Day13McpObservabilityIT'
```

真实模型评测使用固定工具场景，避免工具数据变化污染观测对照；真实 MCP 测试则实际访问
8091 上的独立服务，不依赖模型选择工具。这两个测试验证的边界分别记录。

## 实测结果

2026-09-25，主 Agent 使用本机 Maven 3.8.4 / Oracle JDK 17.0.11 执行并独立核对：

| 检查 | 最终结果 |
|---|---|
| `mvn -DskipTests compile` | BUILD SUCCESS，javac release 17 |
| `mvn test` | BUILD SUCCESS：82 项，80 通过、0 失败、0 错误、2 跳过 |
| `mvn test '-Dtest=Day11PgVectorIT,RetrievalEvaluationIT,Day13ObservabilityIT'` | BUILD SUCCESS：5 项通过，0 失败/错误/跳过 |
| `mvn test '-Dtest=Day13McpObservabilityIT'` | BUILD SUCCESS：1 项通过，真实 Streamable HTTP MCP 调用 |
| MCP Server 本机 Maven 构建 | BUILD SUCCESS（`-DskipTests package`，不代表执行了服务器测试） |
| 主 Agent 独立复核报告 | P50/P95 最近秩、输入/输出 Token 汇总均与逐例结果一致 |
| `git diff --check` | 通过 |

默认跳过的是既有 `RagEmbeddingEvaluationTest` 和 `RagQualityEvaluationTest`，需要显式
`-Drag.eval=true`。本次 Day11/12/13 真实数据库测试另行执行，未用跳过项代替验收。
数据库实测 PostgreSQL 15.19 / pgvector 0.7.2，BGE-M3 1024 维、3 篇知识文档共 23 个块。

[真实模型逐例结果](day13-observability-results.json)和[MCP 链路证据](day13-mcp-observation.json)
为本次 `target/eval-results/` 产物的持久副本，后续运行不会自动覆盖这些历史证据。

| 固定案例 | 状态 | 耗时 ms | 步数 | 逻辑工具调用 |
|---|---|---:|---:|---:|
| D13-RAG | COMPLETED | 10193 | 1 | 0 |
| D13-TOOL | COMPLETED | 11102 | 2 | 4 |
| D13-DB | COMPLETED | 10419 | 2 | 2 |

- P50 = **10419 ms**，P95 = **11102 ms**；平均 **1.6667 步**、**2 次工具调用**。
- Agent 未完成率 **0%**；输入 Token **9456**，输出 Token **2964**，未知用量调用 **0**。
- 模型 5 次调用总计 **31501.69 ms**；检索 3 次总计 **123.76 ms**；工具 6 次总计 **13.18 ms**。
- 请求 Timer 总计 **31714.72 ms**，模型耗时约占 **99.33%**，当前主要延迟来源明确为 LLM。
- 此组 LOCAL 案例的 MCP 调用为 0；另外的真实 MCP 测试完成 1 次远程调用，并断言调用线程与
  请求根的 traceId 相同、spanId 不同。只验收客户端调用链，不声称已验证服务器端 Span 或外部导出后端。

真实 Agent Observation 树中，检索连接到 `db.vector.client.operation` 与 Embedding 观测，
模型连接到 `gen_ai.client.operation`，实际工具路径为
`agent.request → spring.ai.tool → tool.call → tool.attempt`，最后记录 `agent.final`。
MCP 独立验收继续连接 `tool.attempt → mcp.call`。测试还断言实际 PGVector Span 中没有
`db.vector.query.content`、`db.vector.query.filter` 和原始查询内容。

## 子 Agent 完成情况评估

主 Agent 结论：**经审查修正和真实环境验证，Day13 学习验收通过**。

| 子任务（均 GPT-6 Sol / medium） | 主 Agent 审查及验证 | 结论 |
|---|---|---|
| 生产观测链路 | 计划中五组指标齐全；保留 TraceRecorder；跨线程 Scope、真实 OTel bridge、PGVector 安全 Convention；JDK 17 编译与集成通过 | 修正后通过 |
| 固定测试与评测 | 真实 PGVector/BGE-M3/Ollama、隔离 schema、逐例延迟与 Token 对账；真实 MCP transport 与 traceId 验证 | 修正后通过 |
| 边界回归补充 | 已排队任务取消后不执行、调用者中断不重试、执行器拒绝时节点结束且标记失败、null 模型响应受控失败 | 通过 |

修正内容包括：首次编译时 `Nullable` 注解位置错误；测试误把 Spring AI 工具返回的 JSON
字符串当成未编码文本；工具重试计数应发生在再次提交成功后；失败的尝试节点必须结束并标记错误；
框架 `EmptyUsage` 不得当作真实零 Token；缺失 Token 不写伪造样本；PGVector 高基数字段
必须通过官方 Convention 扩展移除。另补充线程池销毁、空模型响应保护，以及中文注释。

保留默认 HYBRID 检索配置，不将观测改动宣称为检索质量或模型答案质量提升。本次未运行全量
Day6 生成模型质量 Eval，也未部署 Prometheus/Zipkin 等外部后端。小样本延迟用于证明观测能力，
不代表性能基准或生产 SLO。未执行 commit 或 push。

## 当天五个问题

1. **解决什么问题？** 能按组件定位一次请求的耗时，并聚合失败、超时、重试、拒绝和 Token。
2. **没有它会怎样？** 仅看逐步文本日志难以串联线程池调用，也难以汇总分位数或解释耗时来源。
3. **属于哪一层？** 主要是 Infrastructure 的观测能力，在 Harness、Model、Tool 与 Data 边界埋点。
4. **如何证明收益？** 用真实三例结果对账请求、模型、检索、工具与 Token；用故障测试证明失败不会漏计。
5. **外部后端不可用怎么办？** 当前没有外部导出依赖，业务执行、进程内指标和 TraceRecorder 仍可使用；
   后续接入远端收集器时再验证导出故障的隔离和降级，不能把未测试行为当成已保证。
