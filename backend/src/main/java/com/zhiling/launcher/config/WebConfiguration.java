package com.zhiling.launcher.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
/**
 * 本地开发时允许 Vite 开发服务器访问后端 API。
 *
 * <p>该配置仅面向 Demo 的本机地址。部署到生产环境后应替换为网关或受控域名白名单，
 * 而非开放跨域访问。</p>
 */
public class WebConfiguration implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 前端通过 Vite 代理访问时可能仍会携带 Origin；列出本机备用端口以支持并行联调。
        registry.addMapping("/api/**")
                .allowedOrigins(
                        "http://localhost:5173", "http://127.0.0.1:5173",
                        "http://localhost:5174", "http://127.0.0.1:5174",
                        "http://localhost:5175", "http://127.0.0.1:5175"
                )
                // Agent Skills 状态切换使用 PATCH，并携带 X-Role 供当前 Demo 的角色校验使用。
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "X-Role");
    }
}
