# Day9：Tool 失败与恢复

## 前置评估

Day8 已具备 HTTP Adapter、可包装的 ToolCallback 与 Trace，无严重阻塞，可以继续 Day9。
本轮保持 JDK17 与既有 Tool Schema，不新增依赖。

## 问题与改动

原 Harness 只对 Future 超时重试，其余执行异常直接终止请求；无法区分权限、限流、瞬时故障与契约异常。
现在由 `ToolFailure` 统一分类，`GuardedToolCallback` 统一执行至多一次重试，HTTP Adapter 不重试。
异常沿 cause 链解包，以兼容 Spring AI 方法反射与 Future 包装；未知异常保守地不重试。

## Failure Matrix

| 场景 | 分类 | 自动重试 | 验证结果 |
|---|---|---|---|
| 503 → 200 | SERVER_ERROR | 1 次 | 恢复，Trace 先 RETRY 后 SUCCESS |
| 持续 500 / 503 | SERVER_ERROR | 1 次 | 两次后返回 ERROR |
| 501 | SERVER_ERROR | 否 | 非白名单服务端错误不重试 |
| 401 / 403 | AUTH_FAILURE | 否 | 仅发起一次请求 |
| 400 | UNKNOWN | 否 | 请求错误不视作可恢复故障 |
| 429，有合法 Retry-After | RATE_LIMITED | 1 次 | 等待后恢复，记录 RETRY |
| 429，头部缺失/非法/超过 3 秒 | RATE_LIMITED | 否 | 不提前重试、不无限等待 |
| 连接失败 | CONNECTION_FAILURE | 1 次 | 两次后返回 ERROR |
| HTTP 读取超时 / Harness 超时 | TIMEOUT | 1 次 | 两次均有 Trace，取消超时 Future |
| 无效 JSON / 空响应 / 字段缺失 / 错误 Content-Type | INVALID_RESPONSE | 否 | 不生成零指标或空成功结果 |
| 策略拒绝 | POLICY_REJECTED | 否 | 正常入口在执行前阻止；回调异常也可分类 |
| 未识别运行时异常 | UNKNOWN | 否 | 不泄露原始异常内容 |
| 调用线程中断 | 中断控制流 | 否 | 取消 Future，恢复中断标志并停止执行 |

`Retry-After` 支持非负整秒和 RFC 1123 HTTP 日期。超出预算时放弃本轮自动重试，不将等待时长裁短。
HTTP 400 在本计划给定的八种分类中归入 UNKNOWN，并固定不重试；未来可单独增加 INVALID_ARGUMENT。

## 数据不可用的表达

最终失败统一返回 `status=ERROR`、`errorType`、`dataAvailable=false`，不携带指标、远端响应体或异常原文。
系统提示词明确禁止把错误理解成零值、健康、无日志或无异常，也禁止模型自行重复相同调用绕过 Harness。
测试通过真实 `ToolCallingManager` 验证错误进入下一轮 `ToolResponseMessage`，而不是丢失在反射异常中。

这验证了失败数据契约与上下文传递，不等于证明任何模型永不幻觉。本轮未执行依赖真实 Ollama 的 Day6 / Day7 IT。

## 编译与测试

- 本机 Apache Maven 3.8.4，Oracle JDK 17.0.11。
- 命令：`mvn clean test`，包含重新编译主代码与测试代码。
- 结果：BUILD SUCCESS；36 项，34 通过，0 失败，0 错误，2 项既有外部模型测试按原有条件跳过。
- 新增 19 项确定性测试，覆盖失败矩阵、恢复、等待边界、中断与模型上下文传递。
- 构建日志：`target/day9-build.log`；详细报告：`target/surefire-reports/`。
- 启动装配测试关闭 MCP 并替换知识加载器，避免普通测试依赖外部 MCP/Ollama；知识加载逻辑仍由现有独立测试覆盖。

## 官方 API 核验

- [Spring RestClient](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html)：`retrieve()` 默认将 HTTP 错误作为异常抛出。
- [RestClientResponseException](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/client/RestClientResponseException.html)：使用 `getStatusCode()`、`getResponseHeaders()` 读取状态和响应头。
- [JDK17 Future](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/Future.html)：`get(timeout, unit)` 和 `cancel(true)`；取消是中断请求，不能保证不响应中断的第三方工具立即终止。
- [RFC 9110 §10.2.3](https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.3)：`Retry-After = HTTP-date / delay-seconds`。
- Spring AI 2.0.1 回调 API 同时通过本机依赖签名与真实回调/ToolCallingManager 测试核验。

## 当天结论

新增组件属于 Harness，HTTP Adapter 只标记契约错误。它解决了可恢复错误无法恢复、不可恢复错误缺乏分类的问题。
503 恢复与 401 单次请求的对照证明策略有效；失败时返回明确的不可用 Observation，供模型说明证据缺失。
保留该改动。真实模型的根因正确率与幻觉率仍需独立 Eval，后续写操作也不能直接复用只读工具重试策略。
