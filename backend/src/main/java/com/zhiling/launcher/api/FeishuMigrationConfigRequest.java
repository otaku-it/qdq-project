package com.zhiling.launcher.api;

import jakarta.validation.constraints.NotBlank;

/** 飞书文档迁移配置提交参数。应用密钥仅用于服务端换取租户令牌，不会回传前端。 */
public record FeishuMigrationConfigRequest(
        @NotBlank(message = "租户名称不能为空") String tenantName,
        @NotBlank(message = "用户角色不能为空") String role,
        @NotBlank(message = "app_id 不能为空") String appId,
        @NotBlank(message = "app_secret 不能为空") String appSecret,
        @NotBlank(message = "企业知识库地址不能为空") String knowledgeBaseUrl
) {
}
