package com.zhiling.launcher.service;

import com.zhiling.launcher.adapter.AgentSkillUploadAdapter;
import com.zhiling.launcher.adapter.AgentSkillUploadAdapter.UploadRequest;
import com.zhiling.launcher.adapter.AgentSkillUploadAdapter.UploadResult;
import com.zhiling.launcher.adapter.DoubaoAgentInitializationAdapter;
import com.zhiling.launcher.adapter.DoubaoAgentInitializationAdapter.InitializationRequest;
import com.zhiling.launcher.adapter.DoubaoAgentInitializationAdapter.InitializationResult;
import com.zhiling.launcher.adapter.SimulatedAgentSkillUploadAdapter;
import com.zhiling.launcher.adapter.SimulatedDoubaoAgentInitializationAdapter;
import com.zhiling.launcher.api.CreateJobRequest;
import com.zhiling.launcher.api.LauncherViews.BlueprintView;
import com.zhiling.launcher.api.LauncherViews.DeliveryView;
import com.zhiling.launcher.api.LauncherViews.EventView;
import com.zhiling.launcher.api.LauncherViews.JobView;
import com.zhiling.launcher.api.LauncherViews.SkillView;
import com.zhiling.launcher.api.LauncherViews.StepView;
import com.zhiling.launcher.api.LauncherViews.TaskView;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 企业管理员和普通用户共用的启动器编排服务。
 *
 * <p>租户注册、飞书组织架构同步属于中台已有能力，不在本服务重复执行。启动器从飞书
 * 会话检查开始，根据当前角色和勾选任务动态构建串行任务树。</p>
 *
 * <p>Demo 用 {@link #tenantSkills} 模拟管理员预置后的租户 Skills 目录。生产环境中该目录
 * 必须替换为带租户隔离、版本、审计和幂等键的持久化配置仓库。</p>
 */
@Service
public class LauncherJobService {

    private static final String TASK_KNOWLEDGE_SYNC = "knowledge-sync";
    private static final String TASK_AGENT_SKILLS = "agent-skills";

    private static final List<TaskView> TASKS = List.of(
            new TaskView(TASK_KNOWLEDGE_SYNC, "同步钉钉知识库", "按当前身份同步企业或个人知识库到飞书", "知识库"),
            new TaskView(TASK_AGENT_SKILLS, "Agent Skills", "选择并上传或初始化飞书豆包工作台 Skills", "智能体")
    );

    private static final List<SkillView> SKILLS = List.of(
            new SkillView("project-plan", "项目计划", "拆分里程碑、排期和负责人", "项目管理"),
            new SkillView("requirement-analysis", "需求梳理", "提炼需求、验收标准和待确认项", "项目管理"),
            new SkillView("risk-tracking", "风险跟踪", "识别风险、责任人和缓解动作", "项目管理"),
            new SkillView("weekly-report", "周报生成", "汇总进展并生成项目周报", "协作效率"),
            new SkillView("scene-analysis-enhanced", "买家秀场景分析", "分析买家实拍场景、风格和核心痛点，生成五 Sheet 分析表", "电商运营")
    );

    /** 仅用于 Demo 的任务仓库；单个任务仍由 synchronized(job) 保护。 */
    private final Map<String, MutableJob> jobs = new ConcurrentHashMap<>();
    /** 管理员上传成功后的测试回退状态；生产运行时以数据库租户状态为准。 */
    private final Map<String, Set<String>> tenantSkills = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
    private final long stepDelayMs;
    private final AgentSkillUploadAdapter skillUploadAdapter;
    private final DoubaoAgentInitializationAdapter agentInitializationAdapter;
    private final AgentSkillService agentSkillService;

    @Autowired
    public LauncherJobService(
            @Value("${launcher.step-delay-ms:850}") long stepDelayMs,
            AgentSkillUploadAdapter skillUploadAdapter,
            DoubaoAgentInitializationAdapter agentInitializationAdapter,
            AgentSkillService agentSkillService
    ) {
        this.stepDelayMs = stepDelayMs;
        this.skillUploadAdapter = skillUploadAdapter;
        this.agentInitializationAdapter = agentInitializationAdapter;
        this.agentSkillService = agentSkillService;
    }

    /** 测试和纯 Java Demo 使用的便捷构造器，执行结果会明确标记为 SIMULATED。 */
    LauncherJobService(long stepDelayMs) {
        this(stepDelayMs, new SimulatedAgentSkillUploadAdapter(), new SimulatedDoubaoAgentInitializationAdapter(), null);
    }

    /** 保留原有上传适配器测试入口，普通用户初始化仍使用模拟实现。 */
    LauncherJobService(long stepDelayMs, AgentSkillUploadAdapter skillUploadAdapter) {
        this(stepDelayMs, skillUploadAdapter, new SimulatedDoubaoAgentInitializationAdapter(), null);
    }

    LauncherJobService(long stepDelayMs, AgentSkillUploadAdapter skillUploadAdapter,
                       DoubaoAgentInitializationAdapter initializationAdapter) {
        this(stepDelayMs, skillUploadAdapter, initializationAdapter, null);
    }

    /** 返回页面可勾选的任务和 Skills，不返回任何飞书凭据。 */
    public BlueprintView getBlueprint() {
        List<SkillView> skills = SKILLS;
        if (agentSkillService != null) {
            try {
                List<SkillView> databaseSkills = agentSkillService.listPublic().stream()
                        .map(skill -> new SkillView(skill.getSkillCode(), skill.getDisplayName(),
                                skill.getDescription() == null ? "" : skill.getDescription(),
                                skill.getCategory() == null ? "通用" : skill.getCategory()))
                        .toList();
                if (!databaseSkills.isEmpty()) skills = databaseSkills;
            } catch (RuntimeException ignored) {
                // 数据库暂不可用时保留历史内存目录，保证原有启动器仍可打开。
            }
        }
        return new BlueprintView("2026.09-launcher", TASKS, skills);
    }

    /**
     * 创建角色化任务树。第一步总是飞书会话检查；没有可复用会话时会转入二维码等待状态。
     */
    public JobView create(CreateJobRequest request) {
        validateRequest(request);
        Instant now = Instant.now();
        MutableJob job = new MutableJob(
                UUID.randomUUID().toString(),
                request.tenantName().trim(),
                request.operatorName().trim(),
                Role.valueOf(request.role()),
                request.larkUser().trim(),
                request.selectedTasks(),
                request.selectedSkills(),
                request.simulateFailure(),
                now,
                buildSteps(request)
        );
        jobs.put(job.id, job);

        boolean shouldSchedule;
        synchronized (job) {
            job.events.add(new EventView(now, "info", "启动器任务已创建，开始检查飞书工作台会话"));
            shouldSchedule = startNextStep(job);
        }
        if (shouldSchedule) {
            scheduleAdvance(job.id);
        }
        return snapshot(job);
    }

    /** 返回当前任务的不可变快照，前端可据此轮询执行树与日志。 */
    public JobView get(String id) {
        return snapshot(requireJob(id));
    }

    /** 用户扫码完成后，恢复暂停在飞书登录检查节点上的任务。 */
    public JobView continueJob(String id) {
        MutableJob job = requireJob(id);
        boolean shouldSchedule;
        synchronized (job) {
            if (job.status != JobStatus.NEEDS_USER_ACTION) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "当前任务不在飞书登录等待状态");
            }
            MutableStep waiting = job.steps.stream()
                    .filter(step -> step.status == StepStatus.WAITING_USER)
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "未找到等待扫码的步骤"));
            Instant now = Instant.now();
            job.status = JobStatus.RUNNING;
            job.currentAction = null;
            job.updatedAt = now;
            if (isBrowserAutomationStep(waiting)) {
                waiting.status = StepStatus.RUNNING;
                waiting.startedAt = now;
                String action = waiting.id.equals("skills-upload")
                        ? "重新执行 Agent Skills 上传"
                        : "重新执行豆包 Agent 初始化";
                job.events.add(new EventView(now, "success", "飞书浏览器登录已确认，" + action));
                shouldSchedule = true;
            } else {
                waiting.status = StepStatus.SUCCEEDED;
                waiting.completedAt = now;
                job.events.add(new EventView(now, "success", "飞书扫码登录已确认，开始串行执行任务树"));
                shouldSchedule = startNextStep(job);
            }
        }
        if (shouldSchedule) {
            scheduleAdvance(id);
        }
        return snapshot(job);
    }

    /** 只重试当前失败节点，成功的同步结果和预置结果保持不变。 */
    public JobView retry(String id) {
        MutableJob job = requireJob(id);
        synchronized (job) {
            if (job.status != JobStatus.FAILED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "当前任务没有可重试的失败步骤");
            }
            MutableStep failed = job.steps.stream()
                    .filter(step -> step.status == StepStatus.FAILED)
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "未找到失败步骤"));
            Instant now = Instant.now();
            failed.status = StepStatus.RUNNING;
            failed.startedAt = now;
            failed.completedAt = null;
            job.status = JobStatus.RUNNING;
            job.currentAction = null;
            job.updatedAt = now;
            job.events.add(new EventView(now, "info", "正在重试：" + failed.title));
        }
        scheduleAdvance(id);
        return snapshot(job);
    }

    private void scheduleAdvance(String id) {
        // 生产环境应由队列/回调推进，而非使用定时器模拟外部 API 和浏览器插件执行。
        executor.schedule(() -> advance(id), stepDelayMs, TimeUnit.MILLISECONDS);
    }

    private void advance(String id) {
        MutableJob job = jobs.get(id);
        if (job == null) {
            return;
        }
        boolean shouldSchedule = false;
        MutableStep uploadStep = null;
        MutableStep initializationStep = null;
        synchronized (job) {
            if (job.status != JobStatus.RUNNING) {
                return;
            }
            MutableStep running = job.steps.stream()
                    .filter(step -> step.status == StepStatus.RUNNING)
                    .findFirst()
                    .orElse(null);
            if (running == null) {
                return;
            }

            Instant now = Instant.now();
            if (running.id.equals("tenant-skills-check") && !areSkillsPreloaded(job)) {
                failForMissingTenantSkills(job, running, now);
                return;
            }
            if (job.simulateFailure && running.id.equals(failureStepId(job)) && job.failedOnce.add(running.id)) {
                running.status = StepStatus.FAILED;
                running.completedAt = now;
                job.status = JobStatus.FAILED;
                job.currentAction = "外部适配器首次调用超时，请检查会话后重试";
                job.updatedAt = now;
                job.events.add(new EventView(now, "error", running.title + "失败：模拟外部调用超时"));
                return;
            }

            if (running.id.equals("skills-upload")) {
                // 浏览器任务可能持续数十秒。离开 job 锁后执行，保证前端轮询仍能读取“执行中”状态。
                uploadStep = running;
            } else if (running.id.equals("doubao-initialization")) {
                // 普通用户初始化同样需要离开 job 锁，避免 Playwright 执行期间阻塞状态查询。
                initializationStep = running;
            } else {
                completeStep(job, running, now);
                shouldSchedule = startNextStep(job);
            }
        }

        if (uploadStep != null) {
            UploadResult result = skillUploadAdapter.upload(new UploadRequest(
                    job.tenantName, job.operatorName, job.selectedSkills
            ));
            synchronized (job) {
                if (job.status != JobStatus.RUNNING || uploadStep.status != StepStatus.RUNNING) {
                    return;
                }
                Instant now = Instant.now();
                uploadStep.executionMode = result.mode();
                uploadStep.resultMessage = result.message();
                if (result.requiresUserAction()) {
                    uploadStep.status = StepStatus.WAITING_USER;
                    job.status = JobStatus.NEEDS_USER_ACTION;
                    job.currentAction = result.message();
                    job.updatedAt = now;
                    job.events.add(new EventView(now, "warning", result.message()));
                    return;
                }
                if (!result.success() || !result.uploadedSkillIds().containsAll(job.selectedSkills)) {
                    uploadStep.status = StepStatus.FAILED;
                    uploadStep.completedAt = now;
                    job.status = JobStatus.FAILED;
                    job.currentAction = result.message();
                    job.updatedAt = now;
                    job.events.add(new EventView(now, "error", "上传 Agent Skills 失败：" + result.message()));
                    return;
                }

                // 只有适配器确认上传成功后，普通用户才能从同一租户目录初始化这些 Skills。
                tenantSkills.computeIfAbsent(tenantKey(job.tenantName), ignored -> ConcurrentHashMap.newKeySet())
                        .addAll(result.uploadedSkillIds());
                if (agentSkillService != null) {
                    try {
                        agentSkillService.markUploaded(job.tenantName, result.uploadedSkillIds(), null);
                    } catch (RuntimeException ignored) {
                        // 数据库状态写入失败不覆盖已完成的真实浏览器上传结果，后续可由管理员重试同步。
                    }
                }
                String eventLevel = "SIMULATED".equals(result.mode()) ? "warning" : "success";
                job.events.add(new EventView(now, eventLevel, result.message()));
                completeStep(job, uploadStep, now);
                shouldSchedule = startNextStep(job);
            }
        }
        if (initializationStep != null) {
            InitializationResult result = agentInitializationAdapter.initialize(new InitializationRequest(
                    job.tenantName,
                    job.operatorName,
                    job.larkUser,
                    job.id,
                    job.selectedSkills
            ));
            synchronized (job) {
                if (job.status != JobStatus.RUNNING || initializationStep.status != StepStatus.RUNNING) {
                    return;
                }
                Instant now = Instant.now();
                initializationStep.executionMode = result.mode();
                initializationStep.resultMessage = result.message();
                if (result.requiresUserAction()) {
                    initializationStep.status = StepStatus.WAITING_USER;
                    job.status = JobStatus.NEEDS_USER_ACTION;
                    job.currentAction = result.message();
                    job.updatedAt = now;
                    job.events.add(new EventView(now, "warning", result.message()));
                    return;
                }
                if (!result.success() || !result.initializedSkillIds().containsAll(job.selectedSkills)) {
                    initializationStep.status = StepStatus.FAILED;
                    initializationStep.completedAt = now;
                    job.status = JobStatus.FAILED;
                    job.currentAction = result.message();
                    job.updatedAt = now;
                    job.events.add(new EventView(now, "error", "初始化豆包 Agent 失败：" + result.message()));
                    return;
                }

                String eventLevel = "SIMULATED".equals(result.mode()) ? "warning" : "success";
                job.events.add(new EventView(now, eventLevel, result.message()));
                completeStep(job, initializationStep, now);
                shouldSchedule = startNextStep(job);
            }
        }
        if (shouldSchedule) {
            scheduleAdvance(id);
        }
    }

    private void completeStep(MutableJob job, MutableStep step, Instant now) {
        step.status = StepStatus.SUCCEEDED;
        step.completedAt = now;
        job.updatedAt = now;
        job.events.add(new EventView(now, "success", step.title + "已完成"));
    }

    private boolean startNextStep(MutableJob job) {
        MutableStep next = job.steps.stream().filter(step -> step.status == StepStatus.PENDING).findFirst().orElse(null);
        Instant now = Instant.now();
        if (next == null) {
            job.status = JobStatus.SUCCEEDED;
            job.progress = 100;
            job.updatedAt = now;
            job.events.add(new EventView(now, "success", "任务树已全部完成，交付结果可用"));
            return false;
        }

        next.startedAt = now;
        job.updatedAt = now;
        if (next.requiresUserAction) {
            next.status = StepStatus.WAITING_USER;
            job.status = JobStatus.NEEDS_USER_ACTION;
            job.currentAction = "请使用飞书扫描二维码登录，登录成功后继续执行任务树";
            job.events.add(new EventView(now, "warning", "未检测到可复用的飞书会话，等待扫码登录"));
            updateProgress(job);
            return false;
        }

        next.status = StepStatus.RUNNING;
        String message = switch (next.id) {
            case "skills-upload" -> "开始执行：" + next.title + "，正在调用技能上传适配器";
            case "doubao-initialization" -> "开始执行：" + next.title + "，正在创建豆包工作任务";
            default -> "开始执行：" + next.title;
        };
        job.events.add(new EventView(now, "info", message));
        updateProgress(job);
        return true;
    }

    private List<MutableStep> buildSteps(CreateJobRequest request) {
        Role role = Role.valueOf(request.role());
        List<MutableStep> steps = new ArrayList<>();
        steps.add(new MutableStep(new StepDefinition(
                "feishu-login", "飞书登录检查", "检测当前用户是否已登录对应的飞书工作台", true
        )));
        if (request.selectedTasks().contains(TASK_KNOWLEDGE_SYNC)) {
            String scope = role == Role.ADMIN ? "企业" : "个人";
            steps.add(new MutableStep(new StepDefinition(
                    "knowledge-sync", "同步钉钉" + scope + "知识库",
                    "通过钉钉与飞书" + scope + "知识库接口执行增量同步", false
            )));
        }
        if (request.selectedTasks().contains(TASK_AGENT_SKILLS)) {
            String skillNames = String.join("、", skillNames(request.selectedSkills()));
            if (role == Role.ADMIN) {
                steps.add(new MutableStep(new StepDefinition(
                        "skills-upload", "上传 Agent Skills",
                        "调用浏览器插件在飞书企业管理平台上传：" + skillNames, false
                )));
            } else {
                steps.add(new MutableStep(new StepDefinition(
                        "tenant-skills-check", "校验租户 Skills",
                        "确认企业管理员已预置当前用户勾选的 Skills", false
                )));
                steps.add(new MutableStep(new StepDefinition(
                        "doubao-initialization", "初始化豆包 Agent",
                        "在飞书豆包工作台为当前用户运行：" + skillNames, false
                )));
            }
        }
        steps.add(new MutableStep(new StepDefinition(
                "completion", "完成结果校验", "校验本次勾选任务的同步或初始化结果", false
        )));
        return steps;
    }

    private void validateRequest(CreateJobRequest request) {
        if (!TASKS.stream().map(TaskView::id).collect(java.util.stream.Collectors.toSet()).containsAll(request.selectedTasks())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "存在不支持的启动任务");
        }
        if (request.selectedTasks().contains(TASK_AGENT_SKILLS)) {
            if (request.selectedSkills().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "已选择 Agent Skills 任务时至少需要勾选一个 Skill");
            }
            Set<String> supportedSkills = SKILLS.stream().map(SkillView::id).collect(java.util.stream.Collectors.toSet());
            if (agentSkillService != null) {
                try {
                    List<SkillView> dbSkills = agentSkillService.getBlueprintSkills();
                    if (!dbSkills.isEmpty()) supportedSkills = dbSkills.stream().map(SkillView::id).collect(java.util.stream.Collectors.toSet());
                } catch (RuntimeException ignored) {
                    // 数据库不可用时保留原有内存目录，避免影响既有任务编排测试。
                }
            }
            if (!supportedSkills.containsAll(request.selectedSkills())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "存在不支持的 Agent Skill");
            }
        }
    }

    private void failForMissingTenantSkills(MutableJob job, MutableStep running, Instant now) {
        Set<String> available = tenantSkills.getOrDefault(tenantKey(job.tenantName), Set.of());
        List<String> missing = job.selectedSkills.stream().filter(skill -> !available.contains(skill)).toList();
        running.status = StepStatus.FAILED;
        running.completedAt = now;
        job.status = JobStatus.FAILED;
        job.currentAction = "租户尚未预置 Skills：" + String.join("、", skillNames(missing)) + "。请企业管理员先完成上传。";
        job.updatedAt = now;
        job.events.add(new EventView(now, "error", "普通用户初始化被阻塞：租户 Skills 目录不满足前置条件"));
    }

    private boolean areSkillsPreloaded(MutableJob job) {
        if (agentSkillService != null) {
            try {
                if (agentSkillService.arePreloaded(job.tenantName, job.selectedSkills)) return true;
            } catch (RuntimeException ignored) {
                // 回退到内存状态，兼容数据库尚未初始化的本地开发环境。
            }
        }
        return tenantSkills.getOrDefault(tenantKey(job.tenantName), Set.of()).containsAll(job.selectedSkills);
    }

    private boolean isBrowserAutomationStep(MutableStep step) {
        return step.id.equals("skills-upload") || step.id.equals("doubao-initialization");
    }

    private List<String> skillNames(List<String> ids) {
        List<SkillView> catalog = SKILLS;
        if (agentSkillService != null) {
            try {
                List<SkillView> dbSkills = agentSkillService.getBlueprintSkills();
                if (!dbSkills.isEmpty()) catalog = dbSkills;
            } catch (RuntimeException ignored) {
                // 使用内存目录作为安全回退。
            }
        }
        List<SkillView> finalCatalog = catalog;
        return ids.stream()
                .map(id -> finalCatalog.stream().filter(skill -> skill.id().equals(id)).findFirst().map(SkillView::name).orElse(id))
                .toList();
    }

    private String failureStepId(MutableJob job) {
        return job.role == Role.ADMIN ? "skills-upload" : "doubao-initialization";
    }

    private JobView snapshot(MutableJob job) {
        synchronized (job) {
            updateProgress(job);
            List<StepView> steps = job.steps.stream().map(step -> new StepView(
                    step.id, step.title, step.description, step.status.name(), step.requiresUserAction,
                    step.executionMode, step.resultMessage, step.startedAt, step.completedAt
            )).toList();
            List<EventView> events = job.events.stream()
                    .sorted(Comparator.comparing(EventView::timestamp).reversed()).limit(12).toList();
            return new JobView(
                    job.id, job.tenantName, job.operatorName, job.role.name(), job.larkUser,
                    List.copyOf(job.selectedTasks), List.copyOf(job.selectedSkills), job.simulateFailure,
                    job.status.name(), job.progress, job.currentAction, job.startedAt, job.updatedAt,
                    deliveries(job), steps, events
            );
        }
    }

    private List<DeliveryView> deliveries(MutableJob job) {
        List<DeliveryView> deliveries = new ArrayList<>();
        String scope = job.role == Role.ADMIN ? "企业级" : "个人级";
        if (job.selectedTasks.contains(TASK_KNOWLEDGE_SYNC)) {
            String name = job.role == Role.ADMIN ? "企业知识库同步" : "个人知识库同步";
            deliveries.add(new DeliveryView(scope, name, "钉钉知识库同步到飞书" + (job.role == Role.ADMIN ? "企业" : "个人") + "知识库", deliveryStatus(job, "knowledge-sync")));
        }
        if (job.selectedTasks.contains(TASK_AGENT_SKILLS)) {
            String stepId = job.role == Role.ADMIN ? "skills-upload" : "doubao-initialization";
            String name = job.role == Role.ADMIN ? "租户 Agent Skills 预置" : "豆包 Agent Skills 初始化";
            String description = String.join("、", skillNames(job.selectedSkills));
            String status = deliveryStatus(job, stepId);
            // 用户侧最终初始化依赖预检。预检失败时，交付结果必须直接反映为失败，不能误导为待执行。
            if (job.role == Role.USER && deliveryStatus(job, "tenant-skills-check").equals("FAILED")) {
                status = "FAILED";
            }
            deliveries.add(new DeliveryView(scope, name, description, status));
        }
        return deliveries;
    }

    private String deliveryStatus(MutableJob job, String stepId) {
        // 使用显式条件判断，避免编译器为枚举 switch 生成额外的 LauncherJobService$1 类。
        // 这样在开发环境增量编译或热重启过程中，即使旧的内部类文件被清理，也不会导致任务创建返回 500。
        for (MutableStep step : job.steps) {
            if (!step.id.equals(stepId)) {
                continue;
            }
            if (step.status == StepStatus.SUCCEEDED) {
                return "READY";
            }
            if (step.status == StepStatus.FAILED) {
                return "FAILED";
            }
            if (step.status == StepStatus.WAITING_USER) {
                return "WAITING_AUTHORIZATION";
            }
            if (step.status == StepStatus.RUNNING) {
                return "PROVISIONING";
            }
            return "PENDING";
        }
        return "PENDING";
    }

    private void updateProgress(MutableJob job) {
        long completed = job.steps.stream().filter(step -> step.status == StepStatus.SUCCEEDED).count();
        job.progress = (int) Math.round(completed * 100.0 / job.steps.size());
    }

    private MutableJob requireJob(String id) {
        MutableJob job = jobs.get(id);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "启动器任务不存在");
        }
        return job;
    }

    private String tenantKey(String tenantName) {
        return tenantName.trim().toLowerCase(Locale.ROOT);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private record StepDefinition(String id, String title, String description, boolean requiresUserAction) {
    }

    private enum Role { ADMIN, USER }

    private enum JobStatus { RUNNING, NEEDS_USER_ACTION, SUCCEEDED, FAILED }

    private enum StepStatus { PENDING, RUNNING, WAITING_USER, SUCCEEDED, FAILED }

    private static final class MutableJob {
        private final String id;
        private final String tenantName;
        private final String operatorName;
        private final Role role;
        private final String larkUser;
        private final List<String> selectedTasks;
        private final List<String> selectedSkills;
        private final boolean simulateFailure;
        private final Instant startedAt;
        private final List<MutableStep> steps;
        private final List<EventView> events = new ArrayList<>();
        private final Set<String> failedOnce = ConcurrentHashMap.newKeySet();
        private JobStatus status = JobStatus.RUNNING;
        private int progress;
        private String currentAction;
        private Instant updatedAt;

        private MutableJob(String id, String tenantName, String operatorName, Role role, String larkUser,
                           List<String> selectedTasks, List<String> selectedSkills, boolean simulateFailure,
                           Instant startedAt, List<MutableStep> steps) {
            this.id = id;
            this.tenantName = tenantName;
            this.operatorName = operatorName;
            this.role = role;
            this.larkUser = larkUser;
            this.selectedTasks = List.copyOf(new LinkedHashSet<>(selectedTasks));
            this.selectedSkills = List.copyOf(new LinkedHashSet<>(selectedSkills));
            this.simulateFailure = simulateFailure;
            this.startedAt = startedAt;
            this.updatedAt = startedAt;
            this.steps = new ArrayList<>(steps);
        }
    }

    private static final class MutableStep {
        private final String id;
        private final String title;
        private final String description;
        private final boolean requiresUserAction;
        private StepStatus status = StepStatus.PENDING;
        private String executionMode;
        private String resultMessage;
        private Instant startedAt;
        private Instant completedAt;

        private MutableStep(StepDefinition definition) {
            this.id = definition.id();
            this.title = definition.title();
            this.description = definition.description();
            this.requiresUserAction = definition.requiresUserAction();
        }
    }
}
