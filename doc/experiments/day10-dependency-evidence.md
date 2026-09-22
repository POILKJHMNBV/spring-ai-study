# Day10：DB / RPC 跨组件证据

## 目标与实现

在 Day9 的 HTTP Adapter 和失败分类之上新增一个 `getDependencyStatus(serviceName)`，
统一返回数据库 P99、活跃/最大连接数及下游 RPC 名称、P99、超时比例。
这是 Tool / Data 层的增量，主 Agent、LOCAL/MCP Kafka 切换和重试机制继续复用。

- `DependencyMetricsClient` 隔离数据源；`app.ops.data-source=HTTP|MOCK` 与其他指标一致。
- HTTP 请求为 `GET /api/v1/services/{serviceName}/dependencies`，使用现有 `app.ops.base-url`。
- P99 单位为毫秒，超时率范围为 `[0,1]`，`0.35` 表示 `35%`。
- `DependencyStatus` 使用包装类型保留字段缺失；HTTP Adapter 验证完整性、服务归属、有限非负数、连接池边界和超时率。
- 首版要求 DB/RPC 快照完整。任一组件缺失均返回 `INVALID_RESPONSE`，不会把缺失值变为零；部分可用性留待后续演进。
- 新工具受服务白名单和调用预算约束；只有 `payment-service` 被允许。
- HTTP 异常由 Day9 Harness 处理：503 最多重试一次，非法契约不重试，失败包含 `dataAvailable=false`，每次尝试进入 Trace。

## 场景与验收证据

| 场景 | 服务 / Kafka 证据 | 独立依赖证据 |
|---|---|---|
| E03 DB 慢 | CPU 45%，线程池 125/200，消费 2100 < 生产 4000，Lag 85000 | DB P99 2500ms，连接池 49/50；RPC 正常 |
| E04 RPC 超时 | CPU 48%，线程池 135/200，消费 2200 < 生产 4000，Lag 90000 | payment-gateway P99 3200ms，超时率 0.35；DB 正常 |
| 依赖数据缺失 | 保留可用的服务 / Kafka 事实 | 不确认 DB/RPC 根因，说明证据不足和补查项 |

E03/E04 的必需工具改为服务、Kafka、依赖指标。`Day10DependencyIT` 将日志替换为空，
通过真实 Ollama 排除“只是读日志猜中”的捷径；另一个案例使依赖工具抛出契约异常。
`DependencyEvidenceTest` 则确定性验证真实 Spring AI 回调、策略、ToolCallingManager 和下一轮模型上下文。

## 本机复现

```powershell
# 主项目编译及默认回归（使用本机 Maven / JDK 17）
mvn clean test

# 独立测试服务编译及 MVC 契约回归
mvn -f service-observer/pom.xml clean test

# 真实模型：需要本机 Ollama 中的 qwen3.5:9b 和 bge-m3
mvn test '-Dtest=Day10DependencyIT,AgentEvaluationIT' '-Dspring.ai.mcp.client.enabled=false'

# 启动 HTTP 场景服务
mvn -f service-observer/pom.xml spring-boot:run
Invoke-RestMethod http://localhost:18081/api/v1/internal/day10/scenario/DB_SLOW
Invoke-RestMethod http://localhost:18081/api/v1/services/payment-service/dependencies
# 其他场景：RPC_TIMEOUT、UNAVAILABLE、NORMAL
```

service-observer 的 Day10 场景同步调整服务指标并返回不含 DB/RPC 数值的辅助日志；
Kafka 的精确 E03/E04 数据由固定 Eval 的 `OpsMockDataProvider` 提供。
手工 HTTP 演示不会自动切换独立 Kafka MCP Server。场景接口只用于本地学习实验。

## 回归中发现的问题

1. 新指标用比例表达，Eval 增加连接池占用率及 RPC 超时百分比换算。
2. 旧数值正则跨行捕获列表序号、知识引用编号，或把 `P99/P95` 中的 95 当数值；已限制匹配范围并移除 `[KB-n]`，增加误报/真实编造数值的回归测试。
3. 本机 Ollama 运行窗口为 4096；扩展后的 Schema、RAG、多轮证据可能挤占上下文。依据官方配置将 `num-ctx` 显式设为 8192。
4. 提示词要求本轮重新获取实时数据、缺失时保留假设，并简化回答，避免把 Memory 中的旧数值混入当前事实。
5. 真实回归出现空正文：Harness 最多补问一次，仍计入六步预算，第二次为空返回失败；新增恢复和停止测试。
6. E02 增加语义相同的“Consumer 数量不足”表述；不降低原有 8/10 主要判断及数值可追溯、会话隔离等门槛。
7. 模型在未知实体澄清中列举了工具参数中的服务名示例，触发原有 E10 门槛；增强当前问题附近的指代约束，要求直接澄清且不列示例。
8. 模型曾把消费/生产速率 2100/4000 的 52.5% 写成 52%，原有数值审计正确拒绝。保留审计门槛，要求直接列出原始速率，避免估算百分比。

## 结果

本机 Maven 3.8.4、Oracle JDK 17.0.11 验收：

| 检查 | 结果 |
|---|---|
| 主项目 `mvn clean test` | BUILD SUCCESS；59 项，0 失败/错误，2 项默认跳过的独立 RAG 模型评测 |
| service-observer `mvn ... clean test` | BUILD SUCCESS；3 项全部通过 |
| `Day10DependencyIT` | 两个测试方法通过，覆盖 E03/E04 无日志和依赖缺失三个真实模型场景 |
| `AgentEvaluationIT` | 最终干净构建后的运行 BUILD SUCCESS；主要判断 10/10，综合通过 10/10 |

最终运行 E01～E10 均通过，包括实时数值可追溯、E06 失败降级、E10 会话隔离及步数上限。
不过此前相同最终提示词的一次运行仅主要判断 8/10、综合通过 7/10（仍满足原有测试门槛）：
E02/E05 未通过主要判断，E09 未满足工具选择评分。因此保留模型波动的限制，
一次 10/10 不能解读成生产质量已达标或所有输入都能稳定通过。

模型回答和 Trace 输出到 `target/eval-results/day10-without-logs.json`、
`day10-missing.json`；全量基线为 `target/eval-results/day6-eval.json`。

## 结论与边界

没有独立依赖工具时，日志文本只能给出线索，难以验证依赖是否真的异常。
新增工具让模型获得服务 → 消费速度 → DB/RPC 的证据链；依赖失败时可退回明确缺失的假设。
所有指标仍来自固定实验数据，不是生产数据库或 RPC 监控，真实只读环境接入属于 Day18。
一次指标快照只能支持相关性假设，不能证明慢 SQL、锁等待等更深层根因。
提示词和真实 Eval 不等于所有输入上的硬保证；结构化输出及业务校验仍按 Day14/15 推进。

## 官方 API 核对

依赖版本保持 Spring AI 2.0.1 / Spring Boot 4.1.1 / Java 17，无新增第三方依赖。

- [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)：`@Tool`、`@ToolParam`、`ToolCallbacks.from` 和手动 `ToolCallingManager`。
- [Spring Framework REST Clients](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html)：`RestClient.get().uri().retrieve().body(...)`。
- [Spring AI Ollama Chat](https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html)：`spring.ai.ollama.chat.num-ctx`；同时核对本机 2.0.1 类的 `getNumCtx()`。
