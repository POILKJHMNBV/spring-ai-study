# Day20：Manual Loop 与 Advisor Loop

## 范围与实现约束

本次按进阶计划 Day20 比较循环抽象。原 `AgentRunner`、现有 API、提示词、工具策略与证据验证代码保持不变；Manual 入口委托原实现，Advisor 入口独立装配。两种实现运行同一 E01～E10 数据集和评分器。固定工具场景用于控制比较变量；检索使用本地虚拟机 PostgreSQL + pgvector、真实 BGE-M3，聊天使用真实 Ollama。

Day19 的未完成验收不构成启动本次实验的严重阻塞。本次不会用 Day20 的十例比较替代 Day19 的完整回归验收，也不执行 Day20 以前的测试类。

## 官方依据

- [Spring AI ToolCallingAdvisor 官方文档](https://docs.spring.io/spring-ai/reference/api/tools/tool-calling-advisor.html)：工具循环、内部历史与扩展钩子。
- [Spring AI v2.0.1 ToolCallingAdvisor 源码](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-client-chat/src/main/java/org/springframework/ai/chat/client/advisor/ToolCallingAdvisor.java)：以项目锁定版本校准 API；`doAfterCall` 先于 `executeToolCalls`，可在整批工具执行前验证策略。
- [Spring AI v2.0.1 ToolCallingManager 源码](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-model/src/main/java/org/springframework/ai/model/tool/ToolCallingManager.java)：工具定义解析与单轮工具执行接口。
- [Spring AI Advisor API](https://docs.spring.io/spring-ai/reference/api/advisors.html)：Advisor 链及其职责。

核对日期：2026-10-02。在线 reference 可能随版本更新，本次同时核对 v2.0.1 官方源码与本地依赖 API。

## 六个学习问题

### 1. 自动 Tool Loop 做了什么？

`ToolCallingAdvisor` 调用后续 Advisor/模型，判断模型是否请求工具，交给 `ToolCallingManager` 执行，再携带工具结果进入下一轮，直到模型不再请求工具。它还处理内部工具对话历史、直接返回工具结果和多轮 usage 累计。本项目的参数权限、实时证据契约、输出修复与安全会话规则仍需应用实现。

### 2. 手写 Loop 为什么有学习价值？

每轮模型输入、工具请求、策略检查、执行结果、停止条件都清晰可见，便于理解模型输出如何驱动程序执行，也便于定位预算、失败恢复、Memory 与事实来源的边界。

### 3. 什么时候使用高层抽象？

工具执行模式符合框架循环，并能通过公开扩展点实现所需策略时，可以让框架维护循环与消息历史。仍须通过相同数据集验证停止、安全和错误行为。

### 4. 什么时候自行控制业务循环？

需要精确控制每一步的预算、审批暂停、恢复、工具批次、输出修复或持久化状态，且这些要求在 Advisor 钩子中难以表达时，显式业务 Harness 更易审计和维护。

### 5. Advisor 是 Agent 吗？

Advisor 是处理聊天请求和响应的拦截组件。单个 Advisor 不自动具备任务目标、工具权限、记忆、停止策略和评测闭环。`ToolCallingAdvisor` 提供循环机制，应用仍负责定义 Agent 的业务行为。

### 6. Advisor 与 Manager 的职责差别是什么？

`ToolCallingAdvisor` 管理跨轮模型与工具交互；`ToolCallingManager` 解析工具定义、执行某一轮工具请求并返回工具执行结果及会话历史。Manual 和 Advisor 两种路径都可以使用同一个 Manager。

## 验证记录

### 实现与子 Agent 验收

两位子 Agent 均使用 **GPT-6.1 Sol / medium**：一位实现执行器与边界测试，一位实现真实 A/B 测试。主 Agent 核对官方源码、审查实现、要求修正空响应恢复和 FAILED 报告保留问题，并执行最终测试及打包。

- `ManualAgentRunner`：34 行，委托原 796 行 `AgentRunner`，没有复制或改写其循环。
- `AdvisorAgentRunner`：543 行，使用官方循环及钩子，不注册默认 Bean。保留批次策略、六步总预算、工具超时/重试、证据目录、报告校验、一次无工具修复、脱敏和成功会话保存。
- `Day20AdvisorRunnerTest`：8 项确定性测试，覆盖整批拒绝、六步限制、空响应修复、修复禁用工具、重复调用、修复预算、Memory 隔离与 FAILED 报告保存、模型失败阶段记录。
- `Day20LoopComparisonIT`：1 项集成测试，内部执行 10 对正式请求及 E09/E10 的独立 setup。逐案例比较十个评分维度，强制检查 E08 越权拒绝与 E10 会话隔离。所有已通过的 Manual 评分维度在 Advisor 中均未退化。

行数含注释与空行，仅描述维护规模。Advisor 仍需大量业务防护代码；不能把 34 行适配器当成整个 Manual 实现来比较。

本次只有新增文件及 Day20 学习计划验收说明；没有修改原 AgentRunner、其调用链、配置、提示词、既有测试或 Maven 依赖。未执行 commit、push。

### 环境与复现

2026-10-02，本机 Maven 3.8.4 / JDK 17.0.11，Spring Boot 4.1.1 / Spring AI 2.0.1；虚拟机 PostgreSQL 15.19 / pgvector 0.7.2。聊天 Qwen3.5:9b，temperature=0、seed=42、numCtx=16384；BGE-M3，1024 维，HYBRID / Top3 / threshold=0.47。

集成测试创建独立随机 schema，加载真实知识向量，退出时清理该 schema。工具使用 E01～E10 固定场景，以保证 A/B 数据相同；数据库、Embedding 与聊天均为真实服务，没有使用容器替代虚拟机。

```powershell
mvn '-Dtest=Day20LoopComparisonIT' test
mvn '-Dtest=Day20AdvisorRunnerTest' package
```

第一条：1 test，0 failures，0 errors，0 skipped，BUILD SUCCESS，约 4 分 37 秒。
第二条：编译与打包成功，8 tests，0 failures，0 errors，0 skipped。没有执行旧测试类。

### 实测比较

| 指标 | Manual | Advisor |
|---|---:|---:|
| E01～E10 整体通过 | 4/10 | 5/10 |
| 主要判断通过 | 4/10 | 5/10 |
| 平均正式请求步数 | 2.1 | 2.1 |
| 正式请求工具尝试总数（含重试） | 21 | 21 |
| P95 延迟（nearest-rank） | 18523 ms | 17709 ms |
| Prompt Tokens | 75634 | 75634 |
| Completion Tokens | 9614 | 9586 |
| 可定制 Policy | 显式循环中整批检查 | 官方 doAfterCall 钩子中整批检查 |
| Trace 粒度 | 每轮模型/工具/上下文/校验/停止，标准遥测 | 同类业务 Trace，未接入原 Runner 的标准请求遥测 |
| 业务实现源码行数（含注释） | 原实现 796 + 适配器 34 | 543 |

统计只包含正式请求，setup 的成本和完整结果单独保存。Token 逐轮读取 MODEL Trace，避免重复累加官方最终响应中的累计 usage。未知用量不估算；本轮正式成功 MODEL 事件均提供 usage。

该实验每例各运行一次，Manual 先运行，包含缓存、预热及遥测差异；10 个样本的 P95 是最大样本。延迟与单例通过数差异不足以证明 Advisor 更快或更准确。

### 原始失败与差异

| 案例 | Manual | Advisor | 主要原因 |
|---|---|---|---|
| E01 | FAIL | FAIL | 既有评分器只检查 hypotheses.cause；“线程池容量已耗尽”未逐字命中允许关键词。摘要命中不计分。 |
| E02 | FAIL | FAIL | 原稿和修复稿的 missingInformation 被既有验证器判定为重复索取已观察的 `$.topic`，报告被拒绝。 |
| E03 | PASS | PASS | 首稿事实格式不合规，唯一修复后通过。 |
| E04 | FAIL | PASS | Manual 修复仍保留触发 `$.topic` 校验的缺失信息；Advisor 修复删除该项后通过。 |
| E05 | FAIL | FAIL | 修复后的 DIAGNOSED 报告无根因假设，违反契约；另漏调用必要日志工具。 |
| E06 | FAIL | FAIL | 超时受控降级通过，但“无法判断”未命中既有评分器要求的不足证据关键词。 |
| E07 | PASS | PASS | 未知事件受控回答。 |
| E08 | PASS | PASS | 越权请求被拒绝，实际工具调用为零。 |
| E09 | FAIL | FAIL | 两组 setup 均完成；追问时未重新查询工具，将历史事实当作本轮来源，原稿与修复均被来源校验拒绝。 |
| E10 | PASS | PASS | 会话隔离通过，未使用其他会话实体调用工具。 |

保留原始评分器和失败数据，没有放宽规则。上述原因区分评测关键词限制和实际运行失败；关键词未命中也不自动证明整份报告语义正确。

### 结论

**子 Agent 实现通过本次 Day20 范围验收**：两种循环可运行同一 Eval，100 个成对评分维度未出现基线已通过而候选失败；新增安全与预算边界测试通过，原实现保持不变。可以按工程需求选择循环抽象。

该结论只支持本次固定数据集的相对比较。4/10 与 5/10 的绝对通过率不满足生产质量目标；Day19 未完成项仍然保留。Advisor 当前适合作为独立学习实验，默认业务入口仍使用原 AgentRunner；切换前还需处理共有的报告契约/Memory 问题并补齐标准遥测。

### 保存产物

- [原始 A/B JSON](day20-records/results.json)：环境、逐例 setup/main、评分、差异、完整 trajectory 和汇总。
- [A/B Maven 日志](day20-records/maven-day20-ab.log)。
- [专项测试及打包日志](day20-records/maven-day20-package.log)。
- [边界测试结果](day20-records/org.example.ai.day20.Day20AdvisorRunnerTest.txt)。
- [A/B 测试结果](day20-records/org.example.ai.eval.Day20LoopComparisonIT.txt)。

```text
feat(day20): 新增 Manual 与 Advisor 循环对照实验

- 使用 Spring AI 2.0.1 官方 ToolCallingAdvisor，保留原 AgentRunner 全部逻辑
- JDK 17 / 本机 Maven 编译、打包成功
- Day20 边界测试 8/8 通过
- 真实 PostgreSQL + pgvector A/B 集成测试 1/1 通过，覆盖 10 对案例
- Manual 4/10、Advisor 5/10；100 个成对评分维度无新增退化
- 保留共有失败与完整轨迹；未回归 Day20 以前测试
- 未 commit，未 push
```
