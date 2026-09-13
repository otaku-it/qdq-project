package com.zhiling.launcher.api;

import java.time.Instant;

/** 飞书文档迁移配置视图，刻意不包含 app_secret。 */
public record FeishuMigrationConfigView(
        String tenantName,
        String appId,
        String knowledgeBaseUrl,
        String parentWikiToken,
        String spaceId,
        String verificationStatus,
        String verificationMessage,
        boolean configured,
        Instant updatedAt
) {
}
