package com.zhiling.launcher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
/**
 * 启动器 Demo 的 Spring Boot 入口。
 *
 * <p>生产环境会在这里加载数据库、飞书和本地 Agent 等适配器；Demo 仅启动
 * REST API 与内存任务状态机。</p>
 */
public class LauncherApplication {

    public static void main(String[] args) {
        SpringApplication.run(LauncherApplication.class, args);
    }
}
