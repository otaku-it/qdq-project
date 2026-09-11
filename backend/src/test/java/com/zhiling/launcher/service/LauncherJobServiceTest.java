package com.zhiling.launcher.service;

import com.zhiling.launcher.adapter.AgentSkillUploadAdapter;
import com.zhiling.launcher.adapter.DoubaoAgentInitializationAdapter;
import com.zhiling.launcher.api.CreateJobRequest;
import com.zhiling.launcher.api.LauncherViews.JobView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LauncherJobServiceTest {

    private final LauncherJobService service = new LauncherJobService(10);

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void adminCanPreloadSkillsAfterFeishuQrLogin() throws Exception {
        JobView job = service.create(new CreateJobRequest(
                "演示租户", "辛海", "ADMIN", "辛海",
                List.of("knowledge-sync", "agent-skills"), List.of("project-plan", "risk-tracking"), false
        ));

        JobView waiting = awaitStatus(job.id(), "NEEDS_USER_ACTION", Duration.ofSeconds(2));
        assertThat(waiting.currentAction()).contains("二维码");
        assertThat(waiting.steps()).anyMatch(step -> step.status().equals("WAITING_USER"));

        service.continueJob(job.id());
        JobView completed = awaitStatus(job.id(), "SUCCEEDED", Duration.ofSeconds(2));
        assertThat(completed.progress()).isEqualTo(100);
        assertThat(completed.steps()).allMatch(step -> step.status().equals("SUCCEEDED"));
        assertThat(completed.deliveries()).allMatch(delivery -> delivery.status().equals("READY"));
        assertThat(completed.larkUser()).isEqualTo("辛海");
        assertThat(completed.role()).isEqualTo("ADMIN");
    }

    @Test
    void ordinaryUserRequiresTenantSkillsThenInitializesDoubaoAgent() throws Exception {
        JobView admin = service.create(new CreateJobRequest(
                "演示租户", "辛海", "ADMIN", "辛海",
                List.of("agent-skills"), List.of("project-plan"), false
        ));
        awaitStatus(admin.id(), "NEEDS_USER_ACTION", Duration.ofSeconds(2));
        service.continueJob(admin.id());
        awaitStatus(admin.id(), "SUCCEEDED", Duration.ofSeconds(2));

        JobView user = service.create(new CreateJobRequest(
                "演示租户", "小王", "USER", "小王",
                List.of("knowledge-sync", "agent-skills"), List.of("project-plan"), false
        ));
        awaitStatus(user.id(), "NEEDS_USER_ACTION", Duration.ofSeconds(2));
        service.continueJob(user.id());
        JobView completed = awaitStatus(user.id(), "SUCCEEDED", Duration.ofSeconds(2));

        assertThat(completed.steps()).anyMatch(step -> step.id().equals("tenant-skills-check") && step.status().equals("SUCCEEDED"));
        assertThat(completed.deliveries()).allMatch(delivery -> delivery.status().equals("READY"));
    }

    @Test
    void ordinaryUserIsBlockedWhenTenantSkillsAreNotPreloaded() throws Exception {
        JobView job = service.create(new CreateJobRequest(
                "未预置租户", "小王", "USER", "小王",
                List.of("agent-skills"), List.of("requirement-analysis"), false
        ));

        awaitStatus(job.id(), "NEEDS_USER_ACTION", Duration.ofSeconds(2));
        service.continueJob(job.id());
        JobView failed = awaitStatus(job.id(), "FAILED", Duration.ofSeconds(2));

        assertThat(failed.currentAction()).contains("请企业管理员先完成上传");
        assertThat(failed.deliveries()).anyMatch(delivery -> delivery.status().equals("FAILED"));
    }

    @Test
    void recordsRealPlaywrightUploadResult() throws Exception {
        AgentSkillUploadAdapter adapter = request -> new AgentSkillUploadAdapter.UploadResult(
                true, false, "PLAYWRIGHT", "已上传并在企业列表中验证", request.skillIds()
        );
        LauncherJobService realUploadService = new LauncherJobService(10, adapter);
        try {
            JobView job = realUploadService.create(new CreateJobRequest(
                    "真实上传租户", "管理员", "ADMIN", "管理员",
                    List.of("agent-skills"), List.of("project-plan"), false
            ));
            awaitStatus(realUploadService, job.id(), "NEEDS_USER_ACTION", Duration.ofSeconds(2));
            realUploadService.continueJob(job.id());
            JobView completed = awaitStatus(realUploadService, job.id(), "SUCCEEDED", Duration.ofSeconds(2));

            assertThat(completed.steps()).anyMatch(step -> step.id().equals("skills-upload")
                    && "PLAYWRIGHT".equals(step.executionMode())
                    && step.resultMessage().contains("企业列表"));
        } finally {
            realUploadService.shutdown();
        }
    }

    @Test
    void failsJobWhenPlaywrightUploadFails() throws Exception {
        AgentSkillUploadAdapter adapter = request -> AgentSkillUploadAdapter.UploadResult.failed(
                "PLAYWRIGHT", "未检测到已登录浏览器会话"
        );
        LauncherJobService failingService = new LauncherJobService(10, adapter);
        try {
            JobView job = failingService.create(new CreateJobRequest(
                    "失败上传租户", "管理员", "ADMIN", "管理员",
                    List.of("agent-skills"), List.of("project-plan"), false
            ));
            awaitStatus(failingService, job.id(), "NEEDS_USER_ACTION", Duration.ofSeconds(2));
            failingService.continueJob(job.id());
            JobView failed = awaitStatus(failingService, job.id(), "FAILED", Duration.ofSeconds(2));

            assertThat(failed.currentAction()).contains("已登录浏览器会话");
            assertThat(failed.steps()).anyMatch(step -> step.id().equals("skills-upload")
                    && step.status().equals("FAILED")
                    && "PLAYWRIGHT".equals(step.executionMode()));
        } finally {
            failingService.shutdown();
        }
    }

    @Test
    void waitsForLoginAndRetriesSameUploadStep() throws Exception {
        int[] attempts = {0};
        AgentSkillUploadAdapter adapter = request -> {
            attempts[0]++;
            if (attempts[0] == 1) {
                return AgentSkillUploadAdapter.UploadResult.waitingForLogin(
                        "PLAYWRIGHT", "受控浏览器已重新打开，请完成飞书扫码授权后继续"
                );
            }
            return new AgentSkillUploadAdapter.UploadResult(
                    true, false, "PLAYWRIGHT", "登录后上传成功", request.skillIds()
            );
        };
        LauncherJobService loginService = new LauncherJobService(10, adapter);
        try {
            JobView job = loginService.create(new CreateJobRequest(
                    "重新登录租户", "管理员", "ADMIN", "管理员",
                    List.of("agent-skills"), List.of("project-plan"), false
            ));
            awaitStatus(loginService, job.id(), "NEEDS_USER_ACTION", Duration.ofSeconds(2));
            loginService.continueJob(job.id());
            JobView waitingAgain = awaitStatus(loginService, job.id(), "NEEDS_USER_ACTION", Duration.ofSeconds(2));
            assertThat(waitingAgain.currentAction()).contains("重新打开");
            assertThat(waitingAgain.steps()).anyMatch(step -> step.id().equals("skills-upload")
                    && step.status().equals("WAITING_USER"));

            loginService.continueJob(job.id());
            JobView completed = awaitStatus(loginService, job.id(), "SUCCEEDED", Duration.ofSeconds(2));
            assertThat(completed.steps()).anyMatch(step -> step.id().equals("skills-upload")
                    && step.status().equals("SUCCEEDED"));
            assertThat(attempts[0]).isEqualTo(2);
        } finally {
            loginService.shutdown();
        }
    }

    @Test
    void recordsPlaywrightDoubaoInitializationResult() throws Exception {
        AgentSkillUploadAdapter uploadAdapter = request -> new AgentSkillUploadAdapter.UploadResult(
                true, false, "PLAYWRIGHT", "管理员预置成功", request.skillIds()
        );
        DoubaoAgentInitializationAdapter initializationAdapter = request ->
                new DoubaoAgentInitializationAdapter.InitializationResult(
                        true,
                        false,
                        "PLAYWRIGHT",
                        "已创建豆包工作任务：https://www.doubao.com/chat/task-1",
                        request.skillIds(),
                        "https://www.doubao.com/chat/task-1"
                );
        LauncherJobService playwrightService = new LauncherJobService(10, uploadAdapter, initializationAdapter);
        try {
            preloadTenantSkill(playwrightService, "Playwright 租户", "project-plan");
            JobView user = playwrightService.create(new CreateJobRequest(
                    "Playwright 租户", "普通用户", "USER", "普通用户",
                    List.of("agent-skills"), List.of("project-plan"), false
            ));
            awaitStatus(playwrightService, user.id(), "NEEDS_USER_ACTION", Duration.ofSeconds(2));
            playwrightService.continueJob(user.id());
            JobView completed = awaitStatus(playwrightService, user.id(), "SUCCEEDED", Duration.ofSeconds(2));

            assertThat(completed.steps()).anyMatch(step -> step.id().equals("doubao-initialization")
                    && step.status().equals("SUCCEEDED")
                    && "PLAYWRIGHT".equals(step.executionMode())
                    && step.resultMessage().contains("/chat/task-1"));
        } finally {
            playwrightService.shutdown();
        }
    }

    @Test
    void waitsForLoginAndRetriesSameDoubaoInitializationStep() throws Exception {
        int[] attempts = {0};
        String[] idempotencyKeys = {null, null};
        AgentSkillUploadAdapter uploadAdapter = request -> new AgentSkillUploadAdapter.UploadResult(
                true, false, "PLAYWRIGHT", "管理员预置成功", request.skillIds()
        );
        DoubaoAgentInitializationAdapter initializationAdapter = request -> {
            idempotencyKeys[attempts[0]] = request.idempotencyKey();
            attempts[0]++;
            if (attempts[0] == 1) {
                return DoubaoAgentInitializationAdapter.InitializationResult.waitingForLogin(
                        "PLAYWRIGHT", "请完成豆包扫码登录后继续"
                );
            }
            return new DoubaoAgentInitializationAdapter.InitializationResult(
                    true, false, "PLAYWRIGHT", "登录后初始化成功",
                    request.skillIds(), "https://www.doubao.com/chat/task-2"
            );
        };
        LauncherJobService loginService = new LauncherJobService(10, uploadAdapter, initializationAdapter);
        try {
            preloadTenantSkill(loginService, "初始化登录租户", "project-plan");
            JobView user = loginService.create(new CreateJobRequest(
                    "初始化登录租户", "普通用户", "USER", "普通用户",
                    List.of("agent-skills"), List.of("project-plan"), false
            ));
            awaitStatus(loginService, user.id(), "NEEDS_USER_ACTION", Duration.ofSeconds(2));
            loginService.continueJob(user.id());
            JobView waitingAgain = awaitStatus(loginService, user.id(), "NEEDS_USER_ACTION", Duration.ofSeconds(2));
            assertThat(waitingAgain.steps()).anyMatch(step -> step.id().equals("doubao-initialization")
                    && step.status().equals("WAITING_USER"));

            loginService.continueJob(user.id());
            JobView completed = awaitStatus(loginService, user.id(), "SUCCEEDED", Duration.ofSeconds(2));
            assertThat(completed.steps()).anyMatch(step -> step.id().equals("doubao-initialization")
                    && step.status().equals("SUCCEEDED"));
            assertThat(attempts[0]).isEqualTo(2);
            assertThat(idempotencyKeys[0]).isEqualTo(idempotencyKeys[1]);
        } finally {
            loginService.shutdown();
        }
    }

    @Test
    void failsJobWhenDoubaoInitializationCannotFindEnterpriseSkill() throws Exception {
        AgentSkillUploadAdapter uploadAdapter = request -> new AgentSkillUploadAdapter.UploadResult(
                true, false, "PLAYWRIGHT", "管理员预置成功", request.skillIds()
        );
        DoubaoAgentInitializationAdapter initializationAdapter = request ->
                DoubaoAgentInitializationAdapter.InitializationResult.failed(
                        "PLAYWRIGHT", "豆包企业技能分组中未找到 project-plan"
                );
        LauncherJobService failingService = new LauncherJobService(10, uploadAdapter, initializationAdapter);
        try {
            preloadTenantSkill(failingService, "初始化失败租户", "project-plan");
            JobView user = failingService.create(new CreateJobRequest(
                    "初始化失败租户", "普通用户", "USER", "普通用户",
                    List.of("agent-skills"), List.of("project-plan"), false
            ));
            awaitStatus(failingService, user.id(), "NEEDS_USER_ACTION", Duration.ofSeconds(2));
            failingService.continueJob(user.id());
            JobView failed = awaitStatus(failingService, user.id(), "FAILED", Duration.ofSeconds(2));

            assertThat(failed.currentAction()).contains("未找到 project-plan");
            assertThat(failed.steps()).anyMatch(step -> step.id().equals("doubao-initialization")
                    && step.status().equals("FAILED")
                    && "PLAYWRIGHT".equals(step.executionMode()));
        } finally {
            failingService.shutdown();
        }
    }

    private void preloadTenantSkill(LauncherJobService targetService, String tenantName, String skillId) throws Exception {
        JobView admin = targetService.create(new CreateJobRequest(
                tenantName, "管理员", "ADMIN", "管理员",
                List.of("agent-skills"), List.of(skillId), false
        ));
        awaitStatus(targetService, admin.id(), "NEEDS_USER_ACTION", Duration.ofSeconds(2));
        targetService.continueJob(admin.id());
        awaitStatus(targetService, admin.id(), "SUCCEEDED", Duration.ofSeconds(2));
    }

    private JobView awaitStatus(String id, String expectedStatus, Duration timeout) throws Exception {
        return awaitStatus(service, id, expectedStatus, timeout);
    }

    private JobView awaitStatus(LauncherJobService targetService, String id, String expectedStatus, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        JobView current = targetService.get(id);
        while (!current.status().equals(expectedStatus) && Instant.now().isBefore(deadline)) {
            Thread.sleep(10);
            current = targetService.get(id);
        }
        assertThat(current.status()).isEqualTo(expectedStatus);
        return current;
    }
}
