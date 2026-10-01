# Day16：人工审批与模拟执行

## 推进评估与任务清单

Day16 可以推进，无严重阻塞。工作开始时工作区干净；本机 Maven 3.8.4、
Oracle JDK 17.0.11 基线编译成功，编译目标为 Java 17。

- 定义不可变动作提案、风险、审批状态、执行结果及审计记录。
- 服务端校验动作、目标与参数，强制人工审批。
- 接入现有 Agent，只向模型开放创建提案的工具。
- 分离提案、批准／拒绝和执行；拒绝、未批准、重复请求不能绕过状态检查。
- 执行器只返回 `SIMULATED_EXECUTION`，不修改 Kafka 或真实服务。
- 编写 Day16 专项测试，通过真实虚拟机 PostgreSQL + pgvector 检索验证集成。
- 主 Agent 独立审查、编译测试、核对结果；禁止 commit、push。

实现和专项测试分别委派给用户指定的 GPT-6 Luna / xhigh（极高）子 Agent。

## 官方依据

2026-09-30 实际读取下列官方资料，均返回 HTTP 200；本地核验快照位于
`target/day16-docs/`。Spring AI 文档页面标识版本为 **2.0.1**，与项目依赖一致。

- [Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)：
  `ToolCallbacks.from(...)` 将 `@Tool` 方法转换为回调；用户控制工具循环适用于外部审批。
- [ChatModel Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools/chatmodel-tool-calling.html)：
  `ChatModel` 返回工具请求，由调用方执行；请求级工具列表替换默认列表。
- [PGVector](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html)：
  schema 初始化需要显式启用；沿用已有数据源和向量维度，在测试隔离 schema 中验证。
- [JDK 17 ConcurrentHashMap](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ConcurrentHashMap.html)：
  并发容器的线程安全不自动保证跨状态检查与执行的整体原子性，审批状态转换仍需同步保护。

动作白名单、审批状态机和审计是本项目的应用规则，不是 Spring AI 内置的审批保证。

## 人工入口与使用边界

第一版通过受信任管理代码调用 `ApprovalService` Java API 完成人工审批，
不提供无鉴权的审批 HTTP 端点。调用方传入的操作人标识用于审计，不能替代身份认证；
宿主必须先确认人工决定，再调用批准或拒绝方法。

模型只能创建提案，不能调用审批和执行方法；审批与执行也不因自然语言中出现
“已批准”而发生。Day17 接入认证和 RBAC 之前，不应将这些 Java 方法直接包装成
公开的无鉴权接口。

提案与审计只保存在当前进程内，重启丢失，不提供持久化、跨节点一致性或生产审计保障。
PostgreSQL + pgvector 用于本次真实 RAG 集成验证，不能据此声称审批数据已持久化。

## 实现与调用方式

新增 `org.example.ai.approval` 包。`ActionProposalTools.proposeScaleConsumer` 是唯一向模型
注册的方法。`ApprovalService` 强制动作 `SCALE_CONSUMER`、目标 `order-topic`、
副本数 2～10、风险 `MEDIUM`、`approvalRequired=true`；原因不能为空且有长度限制。
提案使用独立的强类型 `replicas` 字段，避免无约束的任意参数 Map。

工具参数必须为严格 JSON 对象，只允许 `topic`、`replicas`、`reason`；
附加的 `approved`、`decision` 或 `approvalRequired` 不能改变审批要求。
提案内容使用不可变 record，审批服务按服务端生成的 ID 查找内容，执行入口不接收替换参数。

```text
proposeScaleConsumer → PENDING_APPROVAL
                         ├─ reject  → REJECTED（终态）
                         └─ approve → APPROVED
                                        └─ executeApproved → EXECUTING
                                                               ├─ EXECUTED
                                                               └─ EXECUTION_FAILED（禁止重试）
```

受信任管理代码取得人工确认后可调用以下 Java API。示例中的 `proposalId` 必须来自实际
提案回执或 `findPendingProposals()`，不能把下面几行作为自动批准所有建议的流程：

```java
// 管理代码通过依赖注入取得 ApprovalService，并先向操作人展示完整待审批提案。
ActionProposal proposal = approvalService.findProposal(proposalId).orElseThrow();
// 宿主已验证操作人身份并取得针对该 ID 的明确批准决定后，才调用 approve。
approvalService.approve(proposal.id(), "operator-001");
// 批准本身不执行。独立执行入口仍检查服务端状态，结果只会是模拟回执。
ActionExecutionResult result = approvalService.executeApproved(proposal.id(), "operator-001");
// 拒绝另一个待审批提案时，记录人工理由；该提案随后不能执行。
approvalService.reject(otherProposalId, "operator-001", "当前证据不足，暂不扩容");
// 审计包括创建、去重、批准、拒绝、执行以及被拒绝的审批／执行尝试。
List<ApprovalAuditRecord> audit = approvalService.auditRecords();
```

`AgentToolProvider` 在 LOCAL 和 MCP 模式下都只加入提案工具。Runner 使用 Spring AI
工具列表的替换重载，防止模型默认配置中混入额外回调。`EvidenceCatalog` 只收录四个
只读观测工具，提案回执不能成为实时事实或根因证据。提示词同时说明回执只表示待审批。

相同目标、副本数和原因的待审批提案会复用 ID；这仅是进程内待审批内容去重，
不是跨进程或跨生命周期的请求幂等保证。待审批列表是并发读取快照，操作时仍重新检查状态。

## 验证结果

2026-09-30，主 Agent 使用本机 **Maven 3.8.4 / Oracle JDK 17.0.11** 执行：

```powershell
mvn -DskipTests compile
mvn test '-Dtest=Day16ApprovalFlowTest'
mvn test '-Dtest=Day16ApprovalFlowIT'
git diff --check
```

| 验证 | 最终结果 |
|---|---|
| 生产编译 | 69 个主源码，`javac release 17`，BUILD SUCCESS |
| Day16 单元测试 | 11 项通过，0 失败、0 错误、0 跳过 |
| Day16 集成测试 | 1 项通过，0 失败、0 错误、0 跳过 |
| 空白检查 | `git diff --check` 通过 |

实际环境为虚拟机 **PostgreSQL 15.19 / pgvector 0.7.2**，真实 Ollama **bge-m3**
向量化和检索；查询命中 3 个知识块，并断言知识来源与正文进入 Agent 首轮上下文。
聊天模型使用确定性脚本，2 次模型调用通过实际 AgentRunner、ToolCallingManager 和提案工具完成链路。
这不是实际聊天模型的动作决策质量评测，也不是生产环境扩容测试。

模型调用结束后审计只有 `PROPOSED`，没有自动批准或执行。可信宿主显式批准、执行后，
审计为 `PROPOSED → APPROVED → EXECUTED`，回执为 `SIMULATED_EXECUTION`。
额外预置在模型基础 options 中的 approve、reject、executeApproved 回调未进入请求。
测试创建随机隔离 schema，并在 finally 中清理 schema 与临时 manifest，公共知识表未被修改。

完整实测摘要见 [Day16 集成结果](day16-approval-results.json)；Maven 原始报告在
`target/surefire-reports/`，本次运行结果另写入 `target/eval-results/day16-approval.json`。
Maven 正常编译现有测试源文件，但只执行上述 Day16 测试，未运行 Day16 以前的回归。
所有修改留在工作区，未执行 commit 或 push。

## 子 Agent 完成情况评估

| 子 Agent（GPT-6 Luna / xhigh 极高） | 主 Agent 验收内容 | 结论 |
|---|---|---|
| 生产代码 | 提案与审批执行分离、服务端参数门禁、状态转换、并发最多一次、失败终态、审计及现有 Agent 集成；按审查意见补齐线程可见性、严格 JSON 与证据隔离 | 审查修正后通过 |
| 专项测试 | 单元与真实 PGVector 集成测试已交付；主 Agent 修正初版签名与断言，补充两项独立测试及集成结果落盘，并亲自运行验证 | 补强后通过 |

Day16 四项学习验收完成。当前完成的是单进程、可信 Java 宿主入口下的人工审批与模拟执行，
不包含持久化审批、认证/RBAC、多租户隔离或真实写操作。

## 主 Agent 独立审查

验收过程中修正并复核了以下问题：

- 状态在待审批列表、提案去重和审批线程间读取，需要保证跨线程可见性；状态转换按提案加锁。
- 审计使用规范化的操作人标识，并清理非法 ID 等定位字段中的控制字符。
- 提案参数必须先确认 JSON 对象形状及整数可转换范围，再检查副本数；拒绝重复字段和尾随 JSON。
- 默认模型 options 的工具列表需要替换，防止额外工具混入当前 Agent 请求。
- 提案回执必须从实时证据目录中排除。
- 首次测试编译失败源于 `TraceRecorder.recordTool` 调用漏参；另有模拟回执文本与断言不一致，均由主 Agent 修正。
- 主 Agent 增加两项独立测试，检查未知 ID／重复审批／非法身份审计，以及待审批去重和不可变快照。

## 当天五个问题

1. **解决什么问题？** 将模型提出动作与取得人工批准、执行动作分离。
2. **没有它会怎样？** 模型建议可能直接触发写操作，或者拒绝与重复请求仍导致执行。
3. **属于哪一层？** Harness 与应用执行策略层；模型只生成候选提案。
4. **如何证明收益？** 用未批准、拒绝、伪造字段、并发重复、执行异常等反例检查执行器调用次数和审计，再验证真实 RAG 集成链路。
5. **失败如何降级？** 策略或状态不合法时拒绝并审计；执行异常进入不可重试终态。当前执行器不连接任何外部写系统。
