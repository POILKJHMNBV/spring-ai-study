# Day15 校验阻塞：排查与执行计划

## 范围和基线

本次只修复、验证 Day15，不推进 Day16，不执行 commit 或 push。保留开始工作时已有的未提交修改。使用本机 Maven 3.8.4、Oracle JDK 17.0.11，真实虚拟机 PostgreSQL 15.19 / pgvector 0.7.2、Ollama qwen3.5:9b 和 bge-m3。运维数据使用项目的固定场景，不能称为生产监控数据。

2026-09-29 首次运行 `mvn test '-Dtest=Day15OutputValidationIT'`：1 个测试方法、4 个场景通过，耗时 53.213 秒。E07 9474 ms；E01 35555 ms，首次被 `hypotheses[0].evidence 必须逐字引用 facts.statement` 拒绝，唯一一次修复后通过。原始结果保存在 `target/day15-before-review.json`，日志在 `target/day15-repro.log`。

这证明依赖可连接，也证明当前验收场景存在修复负担；不能由一次通过推断所有请求均不会阻塞。

## 问题与解决方案

1. **重复抄写完整 JSON。** 模型必须在 facts 和每条假设证据里完整重复工具 JSON。语义正确的引用也可能因文本差异被拒绝。为本次工具快照提供稳定证据编号，模型只引用编号与真实 source；Java 校验绑定成功后展开真实快照。保留旧完整 JSON、知识逐字摘录的兼容行为，禁止仅按来源名接受任意事实。
2. **缺失信息匹配过宽。** 当前规则删除分隔符再做子串匹配，未区分已取得的数值与该指标的原因、采样窗口、趋势，也可能命中其他单词或实体。新增可重复反例，规则只拦截明确重复索取的已知信息；保留对当前已知数值、字段绑定和组件边界的拒绝测试。
3. **修复提示缺少易引用证据。** 修复沿用长上下文、被拒报告和首个错误，容易再次重复原错。正常工具循环与修复都提供本次证据编号目录，防止提示累计重复目录。维持一次修复、六步总预算、修复禁用工具、失败不写成功记忆。
4. **旧测试覆盖不足。** 原有真实测试在一个断言失败后不再执行后续真实场景，且只有两个真实输入。新增 Day15 缺陷专项测试及真实 HTTP 请求证据，记录通过和失败，不通过增加重试掩盖失败。
5. **复测补充：等价引用与空正文。** 完整 JSON facts 与编号 evidence 混用时应按同来源、同快照绑定，而不是逐字相等。修复模型曾生成 12722 Token 后返回空正文；按官方 Ollama API 在修复阶段关闭额外思考并限定生成预算，增加真实模型故障注入恢复案例。

排除项：TraceRecorder 注释称 1000 字符截断，实际为 10000，工具正文上限为 8000；本次不能将其认定为截断缺陷。长思考耗时与业务校验耗时需区分，模型单次调用耗时仍由模型/网络决定。

## 执行步骤与验收

| 阶段 | 执行内容 | 交付与通过条件 |
|---|---|---|
| 1 主 Agent | 阅读计划、检查未提交修改、真实 PG/模型基线、启动 HTTP 服务 | 保存基线与实际 Trace，不改写历史实验结果 |
| 2 主 Agent | 核验 Spring AI 2.0.1 官方文档与本机依赖 API | 所有新框架调用有官方文档或固定版本源码依据 |
| 3 子 Agent：day15_fix | 证据编号与展开、提示目录、缺失信息规则、中文注释 | DTO 兼容；不能接受错来源、错指标、失败工具或伪造编号 |
| 4 子 Agent：day15_tests | 先复现误判，再补正反例、Runner 修复边界与真实 IT | 原始失败证据可读；只运行 Day15 测试 |
| 5 主 Agent | 独立审查子 Agent diff，必要时退回修正 | JDK17、中文注释、无无限修复、不绕过证据校验 |
| 6 主 Agent | 本机 Maven 编译、Day15 单测与真实 PG/模型复测，HTTP 端点验证 | 汇总确切测试数、场景状态、耗时、修复次数与局限 |
| 7 主 Agent | 更新实验记录和计划链接，评估两个子 Agent | 用 git message 格式汇总；工作区保留修改，无 commit/push |

两名子 Agent 均按用户指定使用 **GPT-6 Luna / high（高）**，生产代码与测试文件分别负责，主 Agent 统一执行真实模型测试，避免并发争用本机模型。

## 官方依据

本次重新读取下列官方页面，均 HTTP 200，本地核验快照位于 `target/day15-review-docs/`：

- [Structured Output Converters](https://docs.spring.io/spring-ai/reference/api/structured-output/converters.html)：转换属于尽力输出，仍需校验。
- [Schema Validation & Self-Correction](https://docs.spring.io/spring-ai/reference/api/structured-output/validation.html)：失败反馈和有限重试；本项目继续使用手写一次修复，不叠加默认 Advisor 重试。
- [Ollama Chat](https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html)：模型参数、上下文和 thinking 选项以官方接口为准。
- [PGVector](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html)：使用现有 PostgreSQL 配置，在随机隔离 schema 执行集成验收并清理。

证据编号、事实展开与缺失信息规则属于本项目的业务设计，不是 Spring AI 自带事实性保证。校验来源不等于证明自然语言因果推理正确。
