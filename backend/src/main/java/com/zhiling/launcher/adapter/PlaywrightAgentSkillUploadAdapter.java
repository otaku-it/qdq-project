package com.zhiling.launcher.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 通过独立 Node Playwright Worker 执行豆包企业 Skills 上传。
 *
 * <p>Java 进程负责校验技能源文件、生成临时技能包、控制超时并解析结构化结果；浏览器
 * DOM 变化被隔离在 Worker 中。Worker 仅从环境变量读取 CDP 地址或浏览器用户目录，
 * 不把 Cookie、Token 等信息写入命令行和任务日志。</p>
 */
@Component
@ConditionalOnProperty(name = "launcher.skill-upload.mode", havingValue = "playwright")
public class PlaywrightAgentSkillUploadAdapter implements AgentSkillUploadAdapter {

    private final ObjectMapper objectMapper;
    private final String nodeCommand;
    private final Path workerScript;
    private final Path skillDirectory;
    private final Duration timeout;

    public PlaywrightAgentSkillUploadAdapter(
            ObjectMapper objectMapper,
            @Value("${launcher.skill-upload.node-command:node}") String nodeCommand,
            @Value("${launcher.skill-upload.worker-script:automation/upload-agent-skills.mjs}") String workerScript,
            @Value("${launcher.skill-upload.package-directory:skills}") String skillDirectory,
            @Value("${launcher.skill-upload.timeout-seconds:120}") long timeoutSeconds
    ) {
        this.objectMapper = objectMapper;
        this.nodeCommand = nodeCommand;
        this.workerScript = resolveProjectPath(workerScript);
        this.skillDirectory = resolveProjectPath(skillDirectory);
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    @Override
    public UploadResult upload(UploadRequest request) {
        if (!Files.isRegularFile(workerScript)) {
            return UploadResult.failed("PLAYWRIGHT", "Playwright Worker 不存在：" + workerScript);
        }

        Path temporaryDirectory = null;
        try {
            temporaryDirectory = Files.createTempDirectory("zhiling-skill-upload-");
            Map<String, String> packageFiles = packageSkills(request.skillIds(), temporaryDirectory);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tenantName", request.tenantName());
            payload.put("operatorName", request.operatorName());
            payload.put("skills", packageFiles.entrySet().stream()
                    .map(entry -> Map.of("id", entry.getKey(), "file", entry.getValue()))
                    .toList());

            Process process = new ProcessBuilder(nodeCommand, workerScript.toString())
                    .redirectErrorStream(false)
                    .start();
            try (var input = process.getOutputStream()) {
                objectMapper.writeValue(input, payload);
            }

            boolean exited = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                return UploadResult.failed("PLAYWRIGHT", "浏览器上传超过 " + timeout.toSeconds() + " 秒，任务已终止");
            }

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (stdout.isBlank()) {
                return UploadResult.failed("PLAYWRIGHT", stderr.isBlank() ? "Playwright Worker 未返回结果" : safeMessage(stderr));
            }

            WorkerResult workerResult = objectMapper.readValue(stdout, WorkerResult.class);
            if (workerResult.requiresUserAction()) {
                return UploadResult.waitingForLogin("PLAYWRIGHT", safeMessage(workerResult.message()));
            }
            if (process.exitValue() != 0 || !workerResult.success()) {
                return UploadResult.failed("PLAYWRIGHT", safeMessage(workerResult.message()));
            }
            return new UploadResult(true, false, "PLAYWRIGHT", safeMessage(workerResult.message()), workerResult.uploadedSkillIds());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return UploadResult.failed("PLAYWRIGHT", "浏览器上传任务被中断");
        } catch (Exception exception) {
            return UploadResult.failed("PLAYWRIGHT", safeMessage(exception.getMessage()));
        } finally {
            deleteTemporaryDirectory(temporaryDirectory);
        }
    }

    private Map<String, String> packageSkills(List<String> skillIds, Path targetDirectory) throws IOException {
        Map<String, String> packages = new LinkedHashMap<>();
        for (String skillId : skillIds) {
            if (!skillId.matches("[a-z0-9-]+")) {
                throw new IOException("非法 Skill ID：" + skillId);
            }
            Path source = skillDirectory.resolve(skillId).resolve("SKILL.md").normalize();
            if (!source.startsWith(skillDirectory.normalize()) || !Files.isRegularFile(source)) {
                throw new IOException("缺少 Skill 源文件：" + source);
            }
            Path archive = targetDirectory.resolve(skillId + ".skill");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                zip.putNextEntry(new ZipEntry("SKILL.md"));
                Files.copy(source, zip);
                zip.closeEntry();
            }
            packages.put(skillId, archive.toAbsolutePath().toString());
        }
        return packages;
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
            return "豆包企业 Skills 上传失败";
        }
        String firstLine = message.lines().findFirst().orElse("豆包企业 Skills 上传失败");
        return firstLine.length() > 500 ? firstLine.substring(0, 500) : firstLine;
    }

    private static void deleteTemporaryDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时文件清理失败不覆盖上传结果；操作系统仍会在临时目录生命周期内清理。
                }
            });
        } catch (IOException ignored) {
            // 同上，清理失败不改变外部上传的真实结果。
        }
    }

    private record WorkerResult(boolean success, boolean requiresUserAction, String message, List<String> uploadedSkillIds) {
        private WorkerResult {
            uploadedSkillIds = uploadedSkillIds == null ? new ArrayList<>() : List.copyOf(uploadedSkillIds);
        }
    }
}
