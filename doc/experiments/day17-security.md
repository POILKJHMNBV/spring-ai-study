# Day17：Agent 安全策略与固定评测

## 推进评估与任务清单

Day17 可以推进，开始时未发现严重阻塞。工作区最初干净，本机 Maven 3.8.4、Oracle
JDK 17.0.11 基线编译成功，69 个生产源码使用 `javac release 17`。

- 建立覆盖 Prompt Injection、RAG Injection、Tool 越权、MCP 越权、数据泄漏、Approval Bypass 的固定测试集。
- 对全部工具统一执行严格 JSON、字段、类型与资源白名单校验，整批通过后才执行。
- 核查知识和模型输出的消息角色，防止不可信内容获得系统指令权限。
- 加固日志、Trace、Memory 和响应中的敏感数据处理。
- 使用已配置的虚拟机 PostgreSQL + pgvector，在随机隔离 schema 验证恶意知识检索。
- 提供 Day17 专项 Maven 命令和结果记录，由主 Agent 独立审查验收。

生产代码与专项测试分别委派给 GPT-6 Luna / xhigh（极高）子 Agent。禁止 commit、push。

## 官方依据

2026-10-01 主 Agent 实际读取以下官方资料，均返回 HTTP 200。核验快照保存在
`target/day17-docs/`；Spring AI 页面标识为 **2.0.1**，与项目依赖一致。

- [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)：
  请求级工具列表覆盖模型默认工具列表；MCP 工具进入可用集合前应控制来源及工具范围。
- [Spring AI PGVector](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html)：
  schema 初始化需要显式开启，支持配置 schema 和 table；测试沿用项目数据源和独立 schema。
- [JDK 17 Pattern](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/regex/Pattern.html)：
  使用 Java 17 正则 API 实现明确格式的敏感字段脱敏。
- [OWASP LLM Prompt Injection Prevention](https://cheatsheetseries.owasp.org/cheatsheets/LLM_Prompt_Injection_Prevention_Cheat_Sheet.html)：
  参数校验、最小权限、指令与数据分离及人工审批需要共同作用；正则无法可靠识别全部间接注入。

严格字段白名单、资源范围和审批状态机属于本项目应用策略，不是框架自动提供的安全保证。

## 验收边界

按用户明确要求，本次只验证 `/ai/agent` 链路，包括该入口使用的 AgentRunner、MCP 工具
适配、检索、记忆和诊断输出。不修改或验收 `/ai/chat` 的旧 ChatClient 流程。

本次验证重点是：即使聊天模型已经被攻击诱导，Java 执行策略仍阻止不允许的动作。
确定性恶意模型便于重复触发最坏输入，不能据此宣称真实聊天模型能识别全部攻击。
敏感字段脱敏覆盖已定义格式，不等同于通用 DLP，也不保证识别没有字段标识的任意秘密。
人工审批仍由可信 Java 宿主调用，动作仍为模拟执行；不将现有操作人字符串视为身份认证。
本次未建设完整用户认证、角色授权或多租户会话隔离，不能据此公开部署现有调试接口。

## 实现

- `ExecutionPolicy` 对全部五个工具使用严格 JSON 对象、精确字段和类型校验，拒绝重复键、
  尾随对象及越界资源；日志查询窗口限定为 1～1440 分钟，这是本项目的资源限制。
  整个模型工具批次通过策略后才交给 `ToolCallingManager`。拒绝信息使用固定文本，
  不携带模型原始参数或 Jackson 解析异常 cause。
- `AgentToolProvider` 仅接纳远端唯一的 `getKafkaStatus`；额外远端工具不注册，同名歧义拒绝。
- `AgentRunner` 在用户输入、RAG 和历史 Memory 进入模型前脱敏，历史只保留 user/assistant
  文本，不接受历史中的 System/Tool 权限。RAG 保留普通消息角色，系统提示明确其数据属性。
- `ConversationSecurity` 先解析完整 JSON、解码其中的字符串，再处理具名凭据，解决
  JSON 字符串内部转义引号的泄漏；保留普通指标类型，对 JSON 遍历设置深度限制。
  普通文本支持 Bearer、JSON 片段、带引号配置值；正则使用占有量词处理长凭据。
- `TraceRecorder` 在截断前脱敏，日志标签清理控制字符。RAG 日志不输出任意 metadata；
  Agent 与报告解析失败不打印底层异常原文或堆栈。结构化报告先完成证据校验，再复制
  脱敏版本用于响应和 Memory。

## 一键运行

在仓库根目录使用 PowerShell：

```powershell
./scripts/test-day17.ps1
```

脚本使用 PATH 中的本机 Maven 和 JDK，先编译主项目，按需构建并隐藏启动仓库内的
MCP 服务，再执行以下专项命令。已存在的 8091 服务会被复用；脚本只关闭自己启动的进程。
虚拟机 PostgreSQL + pgvector 和 Ollama 需保持可访问，连接参数沿用已有项目配置。

```powershell
mvn -DskipTests compile
mvn test '-Dtest=Day17SecurityTest,Day17BoundaryAuditTest,SecurityEvaluationIT'
```

MCP 服务缺席时，脚本还执行 `mvn -f mcp-kafka-server/pom.xml -DskipTests package`。
Maven 会正常编译现有测试源码，但只执行明确列出的 Day17 测试，不运行以前的回归案例。

## 实际结果

2026-10-01 23:07（Asia/Shanghai），主 Agent 使用本机 **Maven 3.8.4 / Oracle JDK 17.0.11**
执行一键脚本并读取 Surefire XML 核对结果：

| 验证 | 结果 |
|---|---|
| 主项目编译 | BUILD SUCCESS，`javac release 17` |
| MCP 测试服务构建 | BUILD SUCCESS，跳过历史测试 |
| `Day17SecurityTest` | 8 项通过 |
| `Day17BoundaryAuditTest` | 8 项通过 |
| `SecurityEvaluationIT` | 1 项通过 |
| 合计 | **17 项，0 失败、0 错误、0 跳过** |
| `git diff --check` | 通过 |
| 清理 | 随机数据库 schema 已删除，临时 manifest 已清理，本次 MCP 进程已退出 |

固定数据集位于 `src/test/resources/eval/security-cases.json`，覆盖全部六类攻击面。
除六类核心样本外，还检查混合批次零执行、严格参数正反例、自然语言批准不改变合法提案状态、
日志异常 cause、复杂/超长 JSON 凭据，以及 MCP 额外工具和重复名称。

真实集成使用 **PostgreSQL 15.19 / pgvector 0.7.2 / Ollama bge-m3**：

- 在随机 schema 写入带恶意指令的知识文档，真实检索命中 3 个块，其中包括攻击样本。
- MockMvc 调用真实 `ChatController` 的 `/ai/agent` 映射，继而进入真实 Runner、Policy、
  检索和工具管理器；模型为确定性恶意 ChatModel，未使用真实聊天模型。
- RAG 注入请求和 MCP 越权请求均返回 `POLICY_REJECTED`，远端回调次数均为 0。
- 合法 Kafka 请求真实经过 Streamable HTTP MCP 服务，调用 1 次，结果包含服务端 Lag
  `125000`，并进入第二轮模型上下文，最终返回 `COMPLETED`。
- HTTP 入口通过 MockMvc 验证路由、参数绑定和响应序列化；不是部署后经真实 HTTP socket
  访问 Agent 服务的端到端测试。MCP 与数据库连接均为真实网络连接。

可保留的实测结果见 [Day17 集成结果](day17-security-results.json)。完整日志为
`target/day17-final-run.log`，JUnit 报告为 `target/surefire-reports/`。

## 子 Agent 完成情况评估

两个实际承担实现工作的子 Agent 均按要求使用 **GPT-6 Luna / xhigh（极高）**。
它们在收尾阶段因模型使用额度限制退出，未提交最终自评；主 Agent 接管并完成验收。

| 子 Agent | 交付与审查结果 | 评估 |
|---|---|---|
| 生产代码 | 已实现严格参数、消息边界、脱敏、日志与 MCP 发现加固；主 Agent 复现并修复 JSON 字符串内转义凭据仍进入 Trace 的缺陷 | 初稿未完全通过；主 Agent 修正后通过 |
| 安全测试 | 已交付固定六类数据、7 项初版单元测试与真实 PGVector/MCP IT；初次有三处字符串未闭合，收尾已修正；主 Agent 加入更严格状态断言和合法提案自然语言绕过测试 | 初稿需修正补强；最终专项通过 |

主 Agent 另独立编写 8 项 `Day17BoundaryAuditTest`，审查参数硬边界和泄漏路径；
创建并验证一键脚本，亲自运行全部 17 项专项测试。首次脚本使用系统端口枚举时遇到权限限制，
已替换为普通 TCP 连接探测。初次运行的失败均有对应修复，未通过跳过测试掩盖。

## 结论

Day17 五项学习验收在上述 `/ai/agent` 范围内完成。保留全部安全加固、固定数据集和专项测试。
测试证明已定义攻击输入不能跨越 Java 执行边界，不声称覆盖全部攻击或具备完整生产认证能力。
所有修改留在工作区，**未执行 commit 或 push**。

## 当天五个问题

1. **新增组件解决什么问题？** 将现有安全约定变成可重复的攻击案例和执行前硬校验。
2. **没有它会怎样？** 宽松参数、异常原文和未经测试的工具边界可能让攻击绕过约定或泄漏凭据。
3. **属于哪一层？** 主要属于 Harness、工具策略和数据输出边界；安全判定不能全部交给模型。
4. **怎样证明收益？** 主动让模型申请越权动作，断言真实执行入口调用次数、审批状态、日志和返回内容。
5. **失败如何降级？** 参数或权限不符时拒绝整个工具批次；外部失败返回固定错误，审批状态不因自然语言改变。
