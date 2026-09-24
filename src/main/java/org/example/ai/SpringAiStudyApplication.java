package org.example.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring AI 学习项目启动类：Java 生产故障排查助手。
 *
 * <p>项目概述：
 * <ul>
 *   <li>基于 Spring AI + Ollama 构建的 AI Agent，能够调用运维工具排查 Java 生产故障</li>
 *   <li>集成 RAG（检索增强生成），从知识库检索故障排查指南辅助推理</li>
 *   <li>支持 MCP（Model Context Protocol）协议，可切换本地工具或远程 MCP 工具</li>
 *   <li>包含完整的 Agent Harness：超时、重试、安全策略、全链路追踪</li>
 * </ul>
 * </p>
 *
 * <p>核心模块：
 * <ul>
 *   <li>{@code harness} — Agent 执行引擎、工具防护、安全策略、追踪记录</li>
 *   <li>{@code rag} — 知识索引、检索、重排</li>
 *   <li>{@code tool} — 运维工具定义、数据来源抽象、Mock 数据</li>
 *   <li>{@code controller} — HTTP 接口</li>
 * </ul>
 * </p>
 */
@SpringBootApplication
public class SpringAiStudyApplication {

    /**
     * 应用入口：启动 Spring Boot 应用。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(SpringAiStudyApplication.class, args);
    }

}
