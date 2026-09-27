# Day14：结构化诊断报告

## 推进评估与任务清单

Day14 可以推进，无严重阻塞。修改前以本机 Maven 3.8.4 / Oracle JDK 17.0.11
运行 `Day11PgVectorIT,Day13AgentTelemetryTest,Day13ToolTelemetryTest`：15 项中
13 项通过、2 项失败。真实虚拟机 PostgreSQL 15.19 / pgvector 0.7.2 的 3 项全部通过。
两项失败分别是空模型响应丢失当前步数，以及检索失败已受控返回但旧测试仍期待抛异常。
本次在推进结构化输出时修正这两项回归。

实现与测试分别委派给 GPT-6 Luna / xhigh（极高）子 Agent，主 Agent 负责官方依据核验、
独立审查、本机编译测试和验收。任务清单：

- 定义诊断状态、报告、事实、假设与建议动作的 Java 17 record 契约。
- 通过 Spring AI 官方 `BeanOutputConverter` 生成格式要求，并转换最终模型输出。
- 显式检查必填字段、嵌套结构和 JSON 类型，非法输出受控失败。
- 将现有 Agent 与 Eval HTTP 接口改为稳定 JSON 对象，保留展示文本。
- 改造 Eval，使结构化字段与实际工具、检索轨迹参与判定。
- 增加接口、解析失败、Memory 和真实 PGVector / Ollama 集成测试。
- 为新增代码提供中文注释，记录真实验收结果，不执行 commit 或 push。

## 官方依据

2026-09-26 实际读取了 [Spring AI Structured Output Converters](https://docs.spring.io/spring-ai/reference/api/structured-output/converters.html)
（HTTP 200；工作区快照 `target/day14-docs/structured-output.html`）。原
`structured-output-converter.html` 地址已重定向，使用新的 HTTPS 地址读取。

官方说明：`StructuredOutputConverter` 是尽力转换，模型不保证遵守输出要求。
`BeanOutputConverter` 根据目标 Java 类型产生 JSON Schema 和格式说明，再使用
`JsonMapper` 反序列化。因此，只有提示词不能保证稳定契约，应用仍须检查输出。
主 Agent 另用本机 `javap` 核验 Spring AI 2.0.1 的 `BeanOutputConverter(Class<T>,
JsonMapper)`、`getFormat()` 和 `convert(String)`，并读取 Maven 仓库中的官方 sources JAR。
2.0.1 的默认转换器会清理部分 thinking 标签和 Markdown 代码块，且默认忽略未知字段。
本项目在转换前执行严格 JSON 结构检查，是应用明确选择的契约规则，不是框架默认保证。

同日读取 [Spring MVC @ResponseBody](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/responsebody.html)
（HTTP 200）：`@RestController` 的返回对象通过 `HttpMessageConverter` 序列化到响应正文。
因此 Controller 返回 Java 对象，由框架产生 JSON，不把预先拼接的 JSON 当普通字符串返回。

## 对外契约

`/ai/agent` 与 `/ai/eval` 返回 `AgentRunResult` JSON，包含运行状态 `status`、
展示文本 `answer`、`completedSteps`、`trace` 与结构化 `report`。
原来按纯文本消费这两个接口的调用方，需要改为读取 `answer` 或 `report`。

报告六个字段固定为：

| 字段 | 类型与含义 |
|---|---|
| `status` | `DIAGNOSED`、`INSUFFICIENT_EVIDENCE` 或 `FAILED` |
| `summary` | 诊断摘要 |
| `facts` | `statement` 与 `source` 组成的事实列表 |
| `hypotheses` | `cause`、数值 `confidence`、字符串列表 `evidence` |
| `nextActions` | `description` 与布尔值 `requiresApproval` |
| `missingInformation` | 缺失信息的字符串列表 |

运行状态和诊断状态有不同职责：`COMPLETED` 表示成功形成报告，其中允许
`INSUFFICIENT_EVIDENCE` 表示需要补充证据；模型返回 `FAILED` 报告或解析失败时，
运行状态为 `FAILED`，不写成功 Memory。正常完成后的展示文本从报告生成。

兼容旧测试装配的四参 `AgentRunResult` 构造器不把任意成功文本自动升级成合法报告；
真实 Agent 成功路径使用带报告的新契约。

## 范围

本次完成 Day14 的结构契约及缺字段处理。Day15 的完整业务校验和一次修复重试仍是
后续任务；JSON 结构正确不等于模型提供的事实、来源和因果判断真实。
建议动作只是数据，`requiresApproval` 是建议标记，实际执行及审批属于 Day16。

## 编译、测试与子 Agent 评估

2026-09-26，主 Agent 使用本机 Maven 3.8.4 / Oracle JDK 17.0.11 独立执行：

```powershell
mvn -DskipTests compile
mvn test
mvn test '-Dtest=Day11PgVectorIT,Day14StructuredOutputIT,Day13ObservabilityIT'
```

- 编译成功，55 个生产源文件使用 `javac release 17`。
- 默认测试 91 项：89 通过、0 失败、0 错误、2 跳过。
- 第一次集成运行 5 项：全部通过，无失败、错误或跳过。其中 Day14 的 1 个测试方法
  包含 E01 和证据不足两个真实模型场景。
- 默认跳过的是既有 `RagEmbeddingEvaluationTest`、`RagQualityEvaluationTest`，
  需要 `-Drag.eval=true`；本次没有把这两个跳过项算作真实环境通过。
- 测试使用已配置虚拟机 PostgreSQL 15.19 / pgvector 0.7.2、本机 `bge-m3` 和
  `qwen3.5:9b`。真实集成自行创建 UUID 隔离 schema，结束后清理；工具业务数据使用
  固定 MockScenario，以便复核模型输出。

### 独立审查后的修正

- 不直接使用转换器的宽松默认配置；严格拒绝根/嵌套字段缺失、null、错误类型、未知字段、
  未知枚举、重复键、尾随 JSON 和 Markdown 围栏。
- 使用已核验的三参构造传入原样返回文本的 `ResponseTextCleaner`，防止二次清理改写合法
  JSON 字符串中的 `<think>` 字面内容；相应测试已覆盖。
- 修正测试样例未实际删除字段、字符串与枚举比较等问题，E01 集成要求 `overallPass`。
- E01 必须在 `hypotheses.cause` 命中预期根因，检查诊断状态、工具事实来源、证据和置信度；
  只在 answer 或建议动作中提到关键词不能通过。
- 修正空模型响应导致当前步数丢失；检索异常回归按现有受控失败行为断言。
- 失败结果的 Trace 归一为不可变空列表；失败占位报告不掩盖 Eval 中具体的策略拒绝原因。
- 用 Jackson 3.1.5 的 `isString()` 替换已弃用的 `isTextual()`；最终生产编译无该弃用提示。

### 模型实测边界

第一次 Day14 两个场景均正常完成，E01 耗时 8917 ms、2 步、4 个工具，未知事件耗时
8357 ms、1 步、无工具。原始结果保存于
[首次结构化结果](day14-structured-output-initial.json)。

主 Agent 逐例复核发现，第一次 Day13 观测测试虽然通过，其 `D13-TOOL` 场景重复调用
同一批工具，最终触发 `POLICY_REJECTED`（3 个场景未完成率 1/3）。该测试验收的是观测
计数，不要求所有模型场景成功。保留
[首次观测结果](day14-observability-initial.json)，不能将 BUILD SUCCESS 描述成所有诊断均成功。
后续在结构化输出提示中澄清“每轮查询”指每个用户请求，同一请求复用已成功取得的证据，
收集完成后生成报告；保持工具次数上限不变，并重跑 Day13/14 真实模型验证。

另外，首次 E01 报告把 RPC P99=80ms 描述为“升高”，本轮没有历史基线支持这一判断。
这说明结构正确和固定评分通过仍不能证明所有业务陈述正确。完整来源真实性、因果判断、
置信度范围的生产校验及修复仍需 Day15 实现。本次不宣称模型质量全量回归通过，也未重跑
独立 MCP transport、Day10 质量 Eval 或 Day12 全量检索对照。

### 最终复测与验收结论

提示澄清后执行 `mvn test '-Dtest=Day14StructuredOutputIT,Day13ObservabilityIT'`，
2 个测试方法全部通过。2026-09-27 00:07 再执行默认测试，仍为 **91 项、89 通过、
0 失败、0 错误、2 跳过**。最终差异检查 `git diff --check` 通过。

| Day14 固定案例 | 运行状态 | 报告状态 | 步数 | 耗时 |
|---|---|---|---:|---:|
| D14-E01 | COMPLETED | DIAGNOSED | 3 | 19472 ms |
| D14-INSUFFICIENT | COMPLETED | INSUFFICIENT_EVIDENCE | 1 | 5118 ms |

E01 本次在工具执行后的模型调用中出现一次空正文，由既有的一次补问机制恢复；
这不是新增 JSON 修复重试。最终 E01 的工具选择、来源、结构化根因与数值证据评分全部通过。
详见 [最终 Day14 结果](day14-structured-output-results.json)。

最终 Day13 观测结果依然有 `D13-TOOL=POLICY_REJECTED`，其余两个场景 COMPLETED；
提示澄清没有消除重复调用，未完成率仍为 1/3。
[最终观测记录](day14-observability-final.json)保留全部逐例状态。
没有提高工具次数上限、关闭策略保护或把受控失败改记为成功。
该问题需要单独改进模型结束工具调用的稳定性，不影响 Day14 四项结构契约验收，
但不能据此宣称所有既有真实模型场景均正常完成。

| 子 Agent（GPT-6 Luna / xhigh） | 主 Agent 独立评估 | 结论 |
|---|---|---|
| 生产代码 | 核验官方文档与固定版本源码；审查严格解析、报告渲染、JSON 接口、Memory 失败隔离、JDK 17 编译；要求修正文本清理与空响应边界 | 修正后通过 Day14 验收 |
| 测试与 Eval | 审查无效样例真实性、枚举断言、字段评分与真实环境隔离；运行默认测试、PGVector/Ollama 集成；保留失败场景原始记录 | 修正后通过 Day14 验收 |

**主 Agent 结论：Day14 完成；真实模型调用稳定性和完整业务校验仍有上述明确边界。**
学习计划的 Day14 四项已勾选。所有更改保留在工作区，未执行 commit 或 push。

## 当天五个问题

1. **解决什么问题？** 调用方直接消费状态、事实、假设和动作字段，无需解析 Markdown。
2. **没有它会怎样？** 展示文案变化就可能破坏接口解析，缺字段也难以被及时发现。
3. **属于哪一层？** 模型输出和 Harness/API 之间的契约边界。
4. **如何证明收益？** 缺字段、错误类型和无效 JSON 被拒绝；MockMvc 验证真实序列化；
   固定真实模型案例和 E01 字段评分通过。
5. **模型不遵守格式怎么办？** 受控失败、记录停止原因、不保存成功 Memory；
   下一天再增加有上限的输出修复和业务校验。
