package com.zhiling.launcher.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 调用独立 Node Playwright Worker，在普通用户的豆包工作台中创建初始化任务。
 *
 * <p>每个成功结果按启动器任务 ID 缓存在当前进程中，避免页面轮询或节点重试造成重复发送。
 * 生产环境应将该幂等记录迁移到任务数据库。</p>
 */
@Component
@ConditionalOnProperty(name = "launcher.agent-initialization.mode", havingValue = "playwright")
public class PlaywrightDoubaoAgentInitializationAdapter implements DoubaoAgentInitializationAdapter {

    private final ObjectMapper objectMapper;
    private final String nodeCommand;
    private final Path workerScript;
    private final Duration timeout;
    private final Map<String, InitializationResult> completedResults = new ConcurrentHashMap<>();

    public PlaywrightDoubaoAgentInitializationAdapter(
            ObjectMapper objectMapper,
            @Value("${launcher.agent-initialization.node-command:node}") String nodeCommand,
            @Value("${launcher.agent-initialization.worker-script:automation/initialize-doubao-agent.mjs}") String workerScript,
            @Value("${launcher.agent-initialization.timeout-seconds:120}") long timeoutSeconds
    ) {
        this.objectMapper = objectMapper;
        this.nodeCommand = nodeCommand;
        this.workerScript = resolveProjectPath(workerScript);
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    @Override
    public InitializationResult initialize(InitializationRequest request) {
        InitializationResult completed = completedResults.get(request.idempotencyKey());
        if (completed != null) {
            return completed;
        }
        if (!Files.isRegularFile(workerScript)) {
            return InitializationResult.failed("PLAYWRIGHT", "豆包初始化 Worker 不存在：" + workerScript);
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tenantName", request.tenantName());
            payload.put("operatorName", request.operatorName());
            payload.put("larkUser", request.larkUser());
            payload.put("idempotencyKey", request.idempotencyKey());
            payload.put("skills", request.skillIds());

            Process process = new ProcessBuilder(nodeCommand, workerScript.toString())
                    .redirectErrorStream(false)
                    .start();
            try (var input = process.getOutputStream()) {
                objectMapper.writeValue(input, payload);
            }

            boolean exited = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                return InitializationResult.failed(
                        "PLAYWRIGHT",
                        "豆包初始化超过 " + timeout.toSeconds() + " 秒，任务已终止"
                );
            }

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (stdout.isBlank()) {
                return InitializationResult.failed(
                        "PLAYWRIGHT",
                        stderr.isBlank() ? "豆包初始化 Worker 未返回结果" : safeMessage(stderr)
                );
            }

            WorkerResult workerResult = objectMapper.readValue(stdout, WorkerResult.class);
            if (workerResult.requiresUserAction()) {
                return InitializationResult.waitingForLogin("PLAYWRIGHT", safeMessage(workerResult.message()));
            }
            if (process.exitValue() != 0 || !workerResult.success()) {
                return InitializationResult.failed("PLAYWRIGHT", safeMessage(workerResult.message()));
            }

            InitializationResult result = new InitializationResult(
                    true,
                    false,
                    "PLAYWRIGHT",
                    safeMessage(workerResult.message()),
                    workerResult.initializedSkillIds(),
                    workerResult.taskUrl()
            );
            completedResults.putIfAbsent(request.idempotencyKey(), result);
            return completedResults.get(request.idempotencyKey());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return InitializationResult.failed("PLAYWRIGHT", "豆包初始化任务被中断");
        } catch (Exception exception) {
            return InitializationResult.failed("PLAYWRIGHT", safeMessage(exception.getMessage()));
        }
    }

    private static Path resolveProjectPath(String configuredPath) {
        Path direct = Path.of(configuredPath).toAbsolutePath().normalize();
        if (Files.exists(direct)) {
            return direct;
        }
        return Path.of("backend").resolve(configuredPath).toAbsolutePath().normalize();
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "豆包 Agent 初始化失败";
        }
        String firstLine = message.lines().findFirst().orElse("豆包 Agent 初始化失败");
        return firstLine.length() > 500 ? firstLine.substring(0, 500) : firstLine;
    }

    private record WorkerResult(
            boolean success,
            boolean requiresUserAction,
            String message,
            List<String> initializedSkillIds,
            String taskUrl
    ) {
        private WorkerResult {
            initializedSkillIds = initializedSkillIds == null ? new ArrayList<>() : List.copyOf(initializedSkillIds);
        }
    }
}
