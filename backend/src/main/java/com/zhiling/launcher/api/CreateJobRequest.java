package com.zhiling.launcher.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建启动器任务的请求体。
 *
 * <p>租户与飞书组织架构已在智灵中台注册阶段完成同步，因此请求只描述当前登录身份、
 * 本次要执行的任务和 Agent Skills。</p>
 */
public record CreateJobRequest(
        @NotBlank @Size(max = 80) String tenantName,
        @NotBlank @Size(max = 80) String operatorName,
        @NotBlank @Pattern(regexp = "ADMIN|USER") String role,
        @NotBlank @Size(max = 80) String larkUser,
        @NotEmpty @Size(max = 2) List<String> selectedTasks,
        @Size(max = 12) List<String> selectedSkills,
        boolean simulateFailure
) {
    public CreateJobRequest {
        // 归一化为不可变集合，避免后续服务层处理 null 或被调用方修改集合。
        selectedTasks = selectedTasks == null ? List.of() : List.copyOf(selectedTasks);
        selectedSkills = selectedSkills == null ? List.of() : List.copyOf(selectedSkills);
    }
}
