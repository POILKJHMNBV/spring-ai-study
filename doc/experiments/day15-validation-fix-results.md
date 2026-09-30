# Day15 校验阻塞修复与主 Agent 评估

## 问题定位

本次根据 [学习计划](../java-troubleshooting-agent-advanced.md) 修复 Day15，执行步骤见 [详细计划](day15-validation-fix-plan.md)。保留原有未提交修改，没有执行 commit 或 push，也没有运行 Day15 以前的回归套件。

### 实际复现

1. 修复前真实 PostgreSQL/pgvector/BGE-M3/Ollama 集成测试通过，但 E01 首次报告因为 `hypotheses.evidence` 没有逐字重复完整事实而被拒绝，修复后完成，耗时 35555 ms。对应 [基线记录](day15-validation-fix-before.json)。
2. 启动本地 HTTP 服务，调用 `/ai/eval` 查询消费变慢并要求补充采样窗口/趋势，耗时 27543 ms，5 步，其中修复调用耗时 11669 ms。再次因证据逐字匹配触发修复，最终完成；返回的 `missingInformation=[]` 也说明成功状态不保证模型完整满足所有自然语言请求。对应 [HTTP 基线](day15-validation-fix-http-before.json)。
3. 新增四个确定性用例，修复前有三个失败：已知 CPU 数值导致采样窗口/趋势/原因被误拒；`id` 误命中 `requestId`；payment-service 的指标错误阻止索取 inventory-service 的指标。明确索取当前已知 CPU 值的负例仍被拒绝。原始红灯日志在 `target/day15-regression-before.log`。
4. 引入证据编号后的首次真实 IT 仍失败：模型输出“说明文字 (TOOL-1)”，唯一修复后仍未只填写编号。保留 [首次失败记录](day15-validation-fix-first-it.json)，没有通过放宽事实溯源来使测试变绿。随后增加最小格式示例及字段级修复说明。
5. 第二次真实 IT 的 E01 输出合法完整 JSON facts，却用 TOOL-n 引用假设证据；旧字符串比较仍然拒绝等价引用。修复调用耗时 117641 ms、生成 12722 Token 后正文为空。保留 [第二次失败记录](day15-validation-fix-second-it.json)。最终允许来源和完整规范快照都匹配的等价引用，并将修复阶段设为关闭额外思考、最多生成 4096 Token（保留调用方更低的正数上限）。
6. 2026-09-30 第三次 IT 中，故障注入后的真实修复已在 6635 ms 内成功；E01 修好了编号格式，却被“历史基线数据用于对比当前消费速率变化”触发误判。新增历史对照识别及负例，区分比较对象和真正待获取对象。保留 [第三次失败记录](day15-validation-fix-third-it.json)。
7. HTTP 复测发现 Topic 总 Lag 不能满足“各 partition 的 Consumer Lag 分布情况”；新增聚合值与分布明细的区分及反例。该次 25108 ms 的受控失败见 [HTTP 首次复测](day15-validation-fix-http-first-after.json)。随后重新编译并运行全部 Day15 专项及真实 IT。

没有发现无限循环：修复本身受一次预算限制。“卡住”的两部分原因是合法信息被过宽规则拒绝，以及模型在修复调用中重新生成报告所需的时间。单次模型调用的墙钟时间并不受六步计数限制。

排除的错误线索：Trace 注释写 1000 字符，但现有实现为 10000，工具输出上限为 8000；不能将其认定为本次故障根因。新增 3000 字符快照测试证明现有链路保留完整正文。

## 最终实现

- `EvidenceCatalog` 按本次成功工具调用分配 `TOOL-n` 编号，同时绑定完整原始 JSON 与工具名。失败、非 JSON、截断和非法 JSON 不成为可引用快照。
- 模型在 `facts.statement` 和 `hypotheses.evidence` 中引用精确编号。校验后 Java 展开真实完整快照，保持 API、展示和 Memory 的 DTO 结构；仍兼容旧完整 JSON 和知识逐字引用。
- 完整 JSON 和编号可以互相引用，但必须绑定相同来源与完整快照，且该事实已列入报告。仅在目录中存在却未列入 facts 的另一快照不能充当假设证据。
- 工具调用后和唯一一次修复时都提供最新目录；替换旧目录消息，避免重复累计，目录正文按观察数据处理。
- 区分未知 source、工具引用错误和知识摘录错误；指出具体字段及格式，解释性文字应放入 summary/cause，不能凭编号括号接受任意事实。
- 缺失信息按并列短句、ASCII 字段边界、明确中文别名、服务实体和组件范围判断。原因/趋势/窗口与当前标量值分开；同一次调用内另一个嵌套对象的窗口也不能充当当前字段的窗口。
- 保留置信度、DIAGNOSED 证据要求、错实体/错指标、失败来源、一次修复、六步上限、修复禁用工具及失败不写成功 Memory。
- 修复调用使用官方 `disableThinking()` 和 `numPredict(...)`，减少额外思考耗尽上下文的风险；生成 Token 上限不等于网络请求的墙钟超时。
- 真实 IT 使用 `assertAll` 执行两个独立模型场景：一个断言失败仍继续另一个，结果均在 finally 保存。

## 编译与专项单测

环境：Maven 3.8.4 / Oracle JDK 17.0.11；项目固定 Spring AI 2.0.1。

```powershell
mvn -DskipTests compile
mvn test '-Dtest=Day15EvidenceBoundaryTest,Day15OutputValidationTest,Day15ValidationRegressionTest,Day15OutputRepairTest'
mvn test '-Dtest=Day15OutputValidationIT'
git diff --check
```

最终生产编译：56 个源文件，`javac release 17`，成功。

| Day15 套件 | 测试数 | 失败 / 错误 / 跳过 |
|---|---:|---|
| EvidenceBoundary | 17 | 0 / 0 / 0 |
| OutputValidation | 6 | 0 / 0 / 0 |
| ValidationRegression | 17 | 0 / 0 / 0 |
| OutputRepair | 7 | 0 / 0 / 0 |
| 合计 | 47 | 0 / 0 / 0 |

日志：`target/day15-fix-compile.log`、`target/day15-fix-unit.log`。单测中故意注入非法响应产生的 WARN/异常堆栈属于反例，结果以 Surefire 汇总为准。

## 最终真实环境验收

2026-09-30 11:01（Asia/Shanghai）最终 IT：**1 个测试方法通过，覆盖 5 个场景；0 失败、0 错误、0 跳过**。耗时 33.536 秒，日志 `target/day15-fix-it-final.log`，完整结果见 [最终真实环境记录](day15-validation-fix-final.json)。

真实环境为 PostgreSQL **15.19**、pgvector **0.7.2**、bge-m3、qwen3.5:9b。数据库和模型均为真实调用；运维指标来自固定 MockScenario。每次 IT 创建随机隔离 schema，finally 清理 schema 和 manifest。

| 场景 | 运行 / 报告状态 | 步数 | 耗时 | 修复 |
|---|---|---:|---:|---|
| 脚本伪造 source 后修正，真实 PG 检索 | COMPLETED / INSUFFICIENT_EVIDENCE | 2 | 257 ms | 一次成功 |
| 脚本连续非法输出 | FAILED / FAILED（预期） | 2 | 49 ms | 一次失败，不写 Memory |
| 故障注入非法首答，再由真实 Ollama 修复 | COMPLETED / INSUFFICIENT_EVIDENCE | 2 | 4491 ms | 一次成功 |
| 全程真实 Ollama E07 | COMPLETED / INSUFFICIENT_EVIDENCE | 1 | 5428 ms | 无需修复 |
| 全程真实 Ollama E01 | COMPLETED / DIAGNOSED | 3 | 16554 ms | 一次成功 |

E01 最终报告保留四个成功工具的完整快照，hypotheses.evidence 展开为已列 facts.statement。首次仍可能产生自然语言事实，唯一修复能够改为合法编号；本次没有宣称模型首次输出稳定合规。

### HTTP 端点复测

使用本机 Maven 启动应用：

```powershell
mvn spring-boot:run '-Dspring-boot.run.arguments=--spring.profiles.active=local --app.ops.data-source=MOCK --server.address=127.0.0.1 --server.port=18085'
```

调用 `/ai/eval`，使用 E01 场景、`memoryEnabled=false`，问题为“payment-service 的 order-topic 消费明显变慢，请排查主要原因，并说明仍需补充哪些实时指标的采样窗口、趋势和原因分析。”

最终结果 **COMPLETED / DIAGNOSED，3 步、17827 ms、唯一一次修复成功**，详见 [HTTP 最终响应](day15-validation-fix-http-final.json)。初答事实格式不合规被拒，修复后通过；缺失信息保留历史 Lag 基线、消息处理耗时、Rebalance 频率和线程池拒绝次数。自然语言回答未逐项列出问题里的全部采样窗口和趋势，因此这里只验收 Day15 的合法报告与受限修复，不宣称问题覆盖度完美。

`/actuator/health` 返回 UP。启动日志显示已有知识块 reused=23、embedded=0、deleted=0。HTTP 验证结束后已停止本次启动的服务（PID 24588）；没有停止用户原有服务。

### Git message 形式的编译与测试总结

```text
fix(day15): 修复证据引用与缺失信息误判，限制报告修复生成

- 为成功工具快照提供本次证据编号，按来源和完整快照验证等价引用
- 修复指标详情、实体、历史对照和聚合分布的缺失信息误判
- 修复调用关闭额外思考、限制生成预算，保持一次修复与工具禁用
- build: 本机 Maven / JDK 17 编译成功，56 个生产源文件
- test: 47 项 Day15 单测通过，0 失败、0 错误、0 跳过
- integration: 真实 PostgreSQL/pgvector/Ollama，5 场景达到预期
- http: E01 返回 DIAGNOSED，3 步，17.827 秒，一次修复成功
- review: 两名 GPT-6 Luna high 子 Agent 经主 Agent 审查补强后通过
- scope: 未回归 Day15 以前内容；未执行 commit 或 push
```

## 子 Agent 完成情况评估

两名子 Agent 均按用户指定使用 **GPT-6 Luna / high（高）**。

| 子 Agent | 交付 | 主 Agent 发现与补强 | 评估 |
|---|---|---|---|
| day15_fix，生产代码 | 工具目录编号、事实展开、目录提示、缺失信息边界、字段级错误 | 初稿 ASCII 子串与实体处理不充分；要求补强目录顺序、Trace 计数及边界。真实模型又暴露混合引用和提示不清，子 Agent补充匹配 helper，主 Agent完成 Validator 接线、修复生成预算、嵌套对象与历史对照边界 | 初稿不能直接验收；审查修正后通过编译、专项测试和真实 IT |
| day15_tests，专项测试 | 修复前 3 个红灯、ID 正反例、长快照、Runner 目录测试、真实 IT assertAll | 主 Agent增加完整快照/编号混用、未列事实编号拒绝、嵌套窗口、真实历史对照、修复选项断言和真实模型故障注入 | 提交测试有效；主 Agent补强后通过 |

主 Agent独立执行最终 Maven 编译、47 项单测与真实 IT，审查来源绑定、一次修复预算、禁用工具、失败 Memory 及中文注释。所有修改保留在工作区；未执行 commit 或 push。Day16 审批流程不属于本次实现范围。

本次依然观察到模型把“扩容消费者/线程池”的建议标记为 `requiresApproval=false`。当前仅返回建议，没有执行这些操作；审批判定及执行边界须在 Day16 专门解决，不能把 Day15 的来源校验当成操作授权保证。

## 官方依据与保证边界

本次重新读取 Spring AI 官方 [结构化转换](https://docs.spring.io/spring-ai/reference/api/structured-output/converters.html)、[校验与修复](https://docs.spring.io/spring-ai/reference/api/structured-output/validation.html)、[Ollama](https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html)、[PGVector](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html) 文档，HTTP 均为 200。快照在 `target/day15-review-docs/`。

官方明确结构化转换为尽力输出，具体校验错误可以用于有限自我修复。本次没有叠加框架默认重试；清空工具沿用已核验的 `toolCallbacks(List.of())` 替换重载。证据编号、展开和自然语言规则是本项目的应用设计，并非框架自带事实性保证。

另使用本机 Spring AI 2.0.1 `spring-ai-ollama` JAR 的 `javap` 核验 `disableThinking()`、`numPredict(Integer)`、`getNumPredict()`、`getThinkOption()` 和 `ThinkOption.toJsonValue()`，对应官方 Ollama 文档的 Thinking Mode 与 num-predict 说明。单测检查实际修复请求的 thinking=false、生成上限与空工具列表。

缺失信息仍属于有限规则：只识别明确字段、别名及 `*-service` 等约定实体形式，不能证明任意语言的矛盾已被识别。事实引用有效也不证明 summary/cause 的因果解释、趋势推断或建议正确。真实模型存在采样和耗时波动，本次不承诺所有请求首次通过或固定耗时。
