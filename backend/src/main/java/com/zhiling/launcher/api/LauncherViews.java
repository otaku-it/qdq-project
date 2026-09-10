package com.zhiling.launcher.api;

import java.time.Instant;
import java.util.List;

/**
 * REST 层返回的不可变视图模型。
 *
 * <p>服务层内部保留可变任务状态；对外始终返回这些 record 快照，避免 API 消费方
 * 持有并修改执行中的对象。</p>
 */
public final class LauncherViews {

    private LauncherViews() {
    }

    /** 创建任务页所需的任务目录和 Skills 目录。 */
    public record BlueprintView(
            String version,
            List<TaskView> tasks,
            List<SkillView> skills
    ) {
    }

    /** 可由当前登录用户勾选的启动器任务。 */
    public record TaskView(String id, String name, String description, String scope) {
    }

    /** 可上传或初始化的 Agent Skill。 */
    public record SkillView(String id, String name, String description, String category) {
    }

    /** 前端轮询使用的完整任务快照。 */
    public record JobView(
            String id,
            String tenantName,
            String operatorName,
            String role,
            String larkUser,
            List<String> selectedTasks,
            List<String> selectedSkills,
            boolean simulateFailure,
            String status,
            int progress,
            String currentAction,
            Instant startedAt,
            Instant updatedAt,
            List<DeliveryView> deliveries,
            List<StepView> steps,
            List<EventView> events
    ) {
    }

    /** 任务完成后按企业级或个人级展示的交付结果。 */
    public record DeliveryView(
            String scope,
            String name,
            String description,
            String status
    ) {
    }

    /** 单个步骤的当前状态与起止时间。 */
    public record StepView(
            String id,
            String title,
            String description,
            String status,
            boolean requiresUserAction,
            String executionMode,
            String resultMessage,
            Instant startedAt,
            Instant completedAt
    ) {
    }

    /** 追加式运行事件；按时间倒序返回给前端。 */
    public record EventView(Instant timestamp, String level, String message) {
    }
}
