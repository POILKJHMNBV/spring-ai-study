package org.example.observer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Service Observer 启动类：模拟被观测服务的 HTTP 接口。
 *
 * <p>设计背景：Day8 引入 HTTP Tool 后，主工程的 Agent 需要通过 HTTP 调用外部服务获取指标。
 * 该进程模拟真实的生产监控 API，提供可控的测试数据源。</p>
 *
 * <p>核心能力：
 * <ul>
 *   <li>服务指标查询：CPU、内存、线程池等</li>
 *   <li>依赖指标查询：数据库 P99、连接池、RPC 超时率</li>
 *   <li>错误日志查询：最近一段时间的错误日志</li>
 *   <li>故障注入：通过切换场景模拟不同的故障情况</li>
 * </ul>
 * </p>
 */
@SpringBootApplication
public class ServiceObserverApplication {

	/**
	 * Service Observer 入口：启动 Spring Boot 应用。
	 *
	 * @param args 命令行参数
	 */
	public static void main(String[] args) {
		SpringApplication.run(ServiceObserverApplication.class, args);
	}

}
