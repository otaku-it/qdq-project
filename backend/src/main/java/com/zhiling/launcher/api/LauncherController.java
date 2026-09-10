package com.zhiling.launcher.api;

import com.zhiling.launcher.api.LauncherViews.BlueprintView;
import com.zhiling.launcher.api.LauncherViews.JobView;
import com.zhiling.launcher.service.LauncherJobService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/launcher")
/**
 * 启动器前端使用的任务编排 API。
 *
 * <p>任务创建后由前端轮询查询状态。MCP 授权和失败重试采用单独命令端点，
 * 使人工操作和自动执行在 API 层保持明确边界。</p>
 */
public class LauncherController {

    private final LauncherJobService service;

    public LauncherController(LauncherJobService service) {
        this.service = service;
    }

    @GetMapping("/blueprint")
    /**
     * 返回创建任务页面需要的资源目录与步骤定义。
     */
    public BlueprintView getBlueprint() {
        return service.getBlueprint();
    }

    @PostMapping("/jobs")
    @ResponseStatus(HttpStatus.CREATED)
    /**
     * 创建任务并异步启动第一个步骤；响应中的任务快照可立即用于前端渲染。
     */
    public JobView createJob(@Valid @RequestBody CreateJobRequest request) {
        return service.create(request);
    }

    @GetMapping("/jobs/{id}")
    /**
     * 获取最新任务快照，供前端轮询更新进度与运行日志。
     */
    public JobView getJob(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping("/jobs/{id}/continue")
    /**
     * 在用户完成外部授权后恢复任务。
     *
     * <p>只允许处于 {@code NEEDS_USER_ACTION} 状态的任务调用。</p>
     */
    public JobView continueJob(@PathVariable String id) {
        return service.continueJob(id);
    }

    @PostMapping("/jobs/{id}/retry")
    /**
     * 仅重试失败步骤，已成功的前置步骤不会重复执行。
     */
    public JobView retryJob(@PathVariable String id) {
        return service.retry(id);
    }
}
