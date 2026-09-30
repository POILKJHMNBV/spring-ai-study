# Day15：业务输出校验与一次修复

> 本文保留首次实现和验收的历史记录。2026-09-29 用户反馈校验阻塞后，继续排查与修复的范围、方案和步骤见 [Day15 校验修复计划](day15-validation-fix-plan.md)，最终复测与子 Agent 评估见 [修复验收记录](day15-validation-fix-results.md)。

## 推进评估与任务清单

Day15 可以推进，无严重阻塞。修改前工作区干净，本机 Maven 3.8.4 / Oracle JDK
17.0.11 执行 `mvn -DskipTests compile` 成功。Day14 已有结构化 DTO、严格 JSON
转换、工具与检索轨迹，足以增量实现 Output Guard。

用户指定的两个 GPT-6 Luna / xhigh（极高）子 Agent 分别负责生产代码和专项测试；
主 Agent 负责官方依据核验、独立审查、本机编译测试与最终验收。

- 校验 confidence 范围、DIAGNOSED 证据、事实溯源、真实 source 和缺失信息冲突。
- 格式或业务错误最多修复一次，共享 Agent 总步数限制；失败不写成功 Memory。
- 修复阶段禁止执行新工具，校验与修复结果进入 Trace / Eval。
- 新增 Day15 专项单元测试、虚拟机 PostgreSQL + pgvector 集成和真实 Ollama 实验。
- 提供 JDK 17 兼容实现、中文注释、可复现命令和验收边界。
- 仅执行 Day15 相关测试，不回归此前内容，不执行 commit 或 push。

## 官方依据

2026-09-27 主 Agent 实际读取以下 Spring AI **2.0.1** 官方文档，均为 HTTP 200。
工作区快照位于 `target/day15-docs/`；该目录是本地核验产物，不作为提交内容。

- [Structured Output Converters](https://docs.spring.io/spring-ai/reference/api/structured-output/converters.html)：
  转换器是 best effort，模型不保证返回符合要求的结构；`BeanOutputConverter` 负责格式说明和类型转换。
- [Schema Validation & Self-Correction](https://docs.spring.io/spring-ai/reference/api/structured-output/validation.html)：
  官方支持校验失败后把具体错误反馈给模型并有界重试。框架 Advisor 默认重试配置与本项目
  Day15 的“一次修复”要求不同，因此在已有手写循环内实现预算，不叠加 Advisor。
- [ChatModel Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools/chatmodel-tool-calling.html)：
  2.0.1 的 `ChatModel` 不自动执行工具；调用方负责执行及循环。请求级 `toolCallbacks`
  替换默认回调列表，为修复阶段清空工具提供明确依据。
- [Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)：
  工具定义、回调与执行职责遵循官方接口。
- [PGVector](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html)：
  初始化 schema 必须显式启用；沿用现有真实 PostgreSQL 配置，在测试隔离 schema 内初始化。

另以本机 Maven 固定版本 JAR 的 `javap` 和官方 sources JAR 核验 API。
业务证据绑定、missingInformation 约束及一次修复预算属于本项目的应用规则，
不是 Spring AI 对事实正确性的内置保证。

## 实现与证据契约

新增 `EvidenceCatalog`，仅从本次成功工具 Trace 和本次检索块创建不可变目录。
失败、超时、截断及非 JSON 工具正文均不能成为工具事实。目录严格拒绝重复键和尾随
JSON；比较完整对象或数组，允许字段重排、空白及等值数字的表示差异，保留字符串/数字
类型、嵌套字段、数组顺序和实体归属，禁止跨次工具结果拼接。

原 `Fact(statement, source)` 的 JSON 结构保持不变，**statement 的业务约束收紧**：

- 工具事实：statement 是一次成功返回的完整 JSON 文本，source 为该工具原名。
- 知识事实：statement 是 `知识引用：` 加连续逐字摘录，source 必须属于本次检索。
- hypotheses.evidence 精确引用已列出的 facts.statement。
- DIAGNOSED 必须有至少一条工具事实、至少一个假设，且每个假设都有证据。
- 所有状态都检查置信度范围和实际携带的事实来源；FAILED 不允许绕过事实校验。
- INSUFFICIENT_EVIDENCE 必须说明缺失信息；允许明确未确认的占位假设没有证据。
  提示词建议无根因时直接返回空 hypotheses，避免把“无法确定”当成原因。

`missingInformation` 本身表示尚未取得信息，不能重复索取已返回的非 null 指标。
检查覆盖字段名、路径与显式中文别名；区分 DB/RPC 的同名 P99，允许索取历史基线，
不把 null 或空字符串当成已获得值。完整已知事实也不能再次原样列为缺失。
这是一套有限、确定性的规则，不能证明所有自然语言表达都不存在语义冲突。

AgentRunner 将空响应、空正文、JSON 结构错误和业务错误合并到同一个恢复预算，
**最多追加一次模型调用，且仍计入六步上限**。修复保留上下文和被拒报告，并把它声明为
待校正数据。修复调用清空工具列表，返回的工具请求还会在 Java 分支中被拒绝。
只有通过校验且非 FAILED 的报告才保存成功 Memory。

Trace 使用 `VALIDATION` 事件类型，区分 `OUTPUT_VALIDATION` 与 `REPAIR` 名称；
前者记录 PASS/REJECTED，后者记录 STARTED/SUCCEEDED/FAILED/LIMIT。
模型异常、修复仍为空、修复请求工具或报告状态 FAILED 均有终止记录。
修复调用沿用既有模型耗时、Token 与步数记录，不另加不可见的 Advisor 重试。

## 主 Agent 独立审查与修正

首次专项测试 27 项中有 2 项失败，均为“修复请求工具列表应为空”的断言。
主 Agent 读取 Spring AI 2.0.1 官方 sources JAR 的
`DefaultToolCallingChatOptions.Builder` 并用 `javap -c` 核对：
**`toolCallbacks(ToolCallback...)` 追加回调，`toolCallbacks(List)` 替换列表**。
因此 `mutate().toolCallbacks(new ToolCallback[0])` 没有清空旧工具。
改为 `mutate().toolCallbacks(List.of())` 后，27 项通过。源码核验快照为
`target/day15-docs/DefaultToolCallingChatOptions.java`。

其他独立修正包括：

- 严格排除失败、截断、重复键、尾随对象；同数值错指标、错实体、跨快照拼接均拒绝。
- 数值使用十进制规范化，允许 35 和 35.0 等值，但不能将字符串转换成数值；
  保留科学计数法，避免巨大指数展开为超长文本。
- 修复缺失信息对中文别名、无空格字段名、null、历史基线和 DB/RPC 路径的判断。
- 修复 FAILED 报告提前返回而绕过事实来源检查的问题。
- 将失败报告正文传给修复请求；模型修复异常也记录 REPAIR FAILED。
- 加强测试夹具资源释放、工具列表断言；置信度反例使用有效证据，避免因另一处错误
  导致测试假通过。主 Agent 独立增加 17 项证据边界测试。

## 真实环境实验与结果

2026-09-29，使用本机 **Maven 3.8.4 / Oracle JDK 17.0.11**，仅运行 Day15：

```powershell
mvn -DskipTests compile
mvn test '-Dtest=Day15EvidenceBoundaryTest,Day15OutputValidationTest,Day15OutputRepairTest'
mvn test '-Dtest=Day15OutputValidationIT'
git diff --check
```

- 生产编译成功：56 个源文件，`javac release 17`。
- 最终专项单元测试：**29 项通过，0 失败、0 错误、0 跳过**。
  其中证据边界 17 项、业务校验 6 项、Runner 修复 6 项。
- 真实集成：**1 个测试方法通过，包含以下 4 个场景**；0 失败、错误或跳过。
- 环境：虚拟机 **PostgreSQL 15.19 / pgvector 0.7.2**，真实 BGE-M3 向量化与检索，
  真实聊天模型 **qwen3.5:9b**。运维工具数据仍为固定 MockScenario，不能描述为生产监控数据。
- 每次集成创建 UUID 隔离 schema，finally 清理 schema 和临时 manifest；没有改动公共知识表。
- 未执行 Day15 以前的回归测试；上述通过数不包含任何旧测试结果。

| 最终场景 | 运行状态 | 报告状态 | 步数 | 耗时 | 修复结果 |
|---|---|---|---:|---:|---|
| 真实 PG 检索，脚本伪造来源后修正 | COMPLETED | INSUFFICIENT_EVIDENCE | 2 | 291 ms | 一次成功 |
| 脚本连续非法输出 | FAILED（预期） | FAILED | 2 | 50 ms | 一次失败，不写 Memory |
| 真实 Ollama E07 未知事件 | COMPLETED | INSUFFICIENT_EVIDENCE | 1 | 5445 ms | 无需修复 |
| 真实 Ollama E01 工具诊断 | COMPLETED | DIAGNOSED | 3 | 35266 ms | 一次成功 |

原始记录见 [最终 Day15 结果](day15-output-validation-results.json)。
E01 调用了四个工具，其最终 facts 逐项匹配真实执行的完整快照，根因指出线程池饱和。
初次报告的 hypotheses.evidence 没有逐字引用 facts.statement，唯一修复后通过，
Trace 保留完整恢复路径。

### 首次真实模型失败与规则边界

[首次结果](day15-output-validation-initial.json)保留了未通过的真实 E07：模型明确返回
INSUFFICIENT_EVIDENCE，但包含 confidence=0、evidence=[] 的“原因无法确定”占位假设。
初版把“假设必须有证据”扩大到所有状态，与计划中针对 DIAGNOSED 的要求不一致；
它触发了一次修复，真实模型随后耗尽 16384 上下文预算、返回空正文，受控结束为 FAILED，
未继续重试。该次 E07 耗时 139795 ms，后续 E01 未执行，不能把该次实验描述为全通过。

最终将强制非空证据规则限定为 DIAGNOSED，并在提示中明确无根因时 hypotheses=[]。
置信度、实际给出的事实及证据引用仍需校验。随后重新执行本次 Day15 集成，四场景均
达到预期，并补充证据不足占位假设与 FAILED 来源校验两个单测，最终单测共 29 项。

**验证边界：** 来源真实、字段值匹配、引用合法不代表因果解释必然正确。
例如最终 E01 仍把正常 DB/RPC 快照作为低置信度“下游变慢”假设的证据；校验器验证了
引用来源，未证明历史趋势或因果关系。summary、cause 和建议的全部自然语言事实性，
任意语言的缺失信息矛盾，以及模型长思考耗时仍不在本次硬保证内。
修复次数受限不等于每次模型调用耗时受限；本次没有宣称真实模型必然一次修复成功。

## 子 Agent 完成情况评估

| 子 Agent（均为 GPT-6 Luna / xhigh 极高） | 主 Agent 验收内容 | 结论 |
|---|---|---|
| 生产代码 | EvidenceCatalog、业务校验、Runner 一次修复和 Trace；补齐 API 重载核验、来源校验和缺失字段边界后，通过 JDK 17 编译及专项测试 | 审查修正后通过 |
| 测试与 Eval | 业务反例、修复预算、工具隔离、Memory、真实 PG 检索、真实 Ollama、结果落盘和资源清理；主 Agent 独立运行并复核逐例结果 | 审查补强后通过 |

主 Agent 结论：Day15 四项验收完成，学习计划已更新。所有修改保留在工作区，
没有执行 commit 或 push。后续可以在 Day16 引入建议与执行分离的审批流程。

## 当天五个问题

1. **新增组件解决什么？** 将“能反序列化”推进为“事实和引用能绑定本次证据”，并提供一次恢复机会。
2. **没有它会怎样？** 虚构 source、错位指标和越界 confidence 会作为合法 JSON 返回给调用方。
3. **属于哪一层？** Harness 的模型输出边界，使用 Tool/RAG 的观察数据。
4. **如何证明收益？** 独立负例被拒绝，脚本格式错误和真实模型报告在唯一修复内恢复；Trace 可追溯。
5. **失败如何降级？** 返回 FAILED 报告，不写成功记忆，不在修复阶段执行工具，不继续无限修复。
