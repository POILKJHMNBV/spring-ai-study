package org.example.ai.tool.dto;

/**
 * 同一服务的 DB/RPC 只读指标快照；时间单位为毫秒，超时率为 0～1 的比例。
 * 包装类型保留缺失值，HTTP Adapter 必须校验后才能作为有效证据交给模型。
 */
public record DependencyStatus(String serviceName, Database database, Rpc rpc) {
    /** 数据库 P99 与连接池占用；activeConnections 不得超过 maxConnections。 */
    public record Database(Double p99Ms, Integer activeConnections, Integer maxConnections) { }

    /** 下游依赖名、调用 P99、超时比例；0.35 表示 35%。 */
    public record Rpc(String dependency, Double p99Ms, Double timeoutRate) { }
}
