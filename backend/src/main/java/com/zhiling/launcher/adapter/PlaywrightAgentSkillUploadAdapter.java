package com.zhiling.launcher.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
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

            Process process = startWorkerProcess();
            try (var input = process.getOutputStream()) {
                objectMapper.writeValue(input, payload);
            }

            // 必须在等待进程结束前持续消费两个输出管道。Playwright/Chrome 可能向 stderr
            // 写入较多诊断信息，若等进程结束后才读取，管道写满会让 Worker 永久阻塞。
            CompletableFuture<String> stdoutFuture = readAsync(process.getInputStream());
            CompletableFuture<String> stderrFuture = readAsync(process.getErrorStream());

            boolean exited = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            if (!exited) {
                terminateProcessTree(process);
                process.waitFor(2, TimeUnit.SECONDS);
                return UploadResult.failed("PLAYWRIGHT", "浏览器上传超过 " + timeout.toSeconds() + " 秒，任务已终止");
            }

            String stdout = awaitOutput(stdoutFuture);
            String stderr = awaitOutput(stderrFuture);
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

    private Process startWorkerProcess() throws IOException {
        ProcessBuilder builder = new ProcessBuilder(nodeCommand, workerScript.toString())
                .redirectErrorStream(false);
        // 统一 Worker 的工作目录，避免从 IDE、systemd 或项目根目录启动时 Node 的模块
        // 搜索路径和相对配置不一致。脚本及技能包仍使用绝对路径，不改变现有部署方式。
        Path workingDirectory = workerScript.getParent();
        if (workingDirectory != null && Files.isDirectory(workingDirectory)) {
            builder.directory(workingDirectory.toFile());
        }
        return builder.start();
    }

    private static CompletableFuture<String> readAsync(java.io.InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
            } catch (IOException exception) {
                return "";
            }
        });
    }

    private static String awaitOutput(CompletableFuture<String> output) {
        try {
            return output.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "";
        } catch (ExecutionException | TimeoutException exception) {
            return "";
        }
    }

    private static void terminateProcessTree(Process process) {
        process.descendants().forEach(child -> child.destroyForcibly());
        process.destroyForcibly();
    }

    Map<String, String> packageSkills(List<String> skillIds, Path targetDirectory) throws IOException {
        Map<String, String> packages = new LinkedHashMap<>();
        for (String skillId : skillIds) {
            if (!skillId.matches("[a-z0-9-]+")) {
                throw new IOException("非法 Skill ID：" + skillId);
            }
            Path archive = prepareSkillPackage(skillId, targetDirectory);
            packages.put(skillId, archive.toAbsolutePath().toString());
        }
        return packages;
    }

    private Path prepareSkillPackage(String skillId, Path targetDirectory) throws IOException {
        Path baseDirectory = skillDirectory.toAbsolutePath().normalize();
        Path zipSource = baseDirectory.resolve(skillId + ".zip").normalize();
        if (Files.isRegularFile(zipSource)) {
            return copyValidatedArchive(zipSource, targetDirectory.resolve(skillId + ".zip"));
        }

        Path skillSource = baseDirectory.resolve(skillId + ".skill").normalize();
        if (Files.isRegularFile(skillSource)) {
            return copyValidatedArchive(skillSource, targetDirectory.resolve(skillId + ".skill"));
        }

        // 保留原有目录模式：只有 SKILL.md 时继续生成豆包支持的 .skill 压缩包。
        Path markdownSource = baseDirectory.resolve(skillId).resolve("SKILL.md").normalize();
        if (!markdownSource.startsWith(baseDirectory) || !Files.isRegularFile(markdownSource)) {
            throw new IOException("缺少 Skill 源文件，支持：" + skillId + ".zip、"
                    + skillId + ".skill 或 " + skillId + "/SKILL.md");
        }
        Path archive = targetDirectory.resolve(skillId + ".skill");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("SKILL.md"));
            Files.copy(markdownSource, zip);
            zip.closeEntry();
        }
        return archive;
    }

    private Path copyValidatedArchive(Path source, Path target) throws IOException {
        try (ZipFile zip = new ZipFile(source.toFile())) {
            ZipEntry skillMarkdown = zip.getEntry("SKILL.md");
            if (skillMarkdown != null && !skillMarkdown.isDirectory()) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                return target;
            }

            String wrapperDirectory = findSingleSkillWrapper(zip);
            if (wrapperDirectory == null) {
                throw new IOException("Skill 压缩包根目录缺少 SKILL.md：" + source);
            }
            normalizeWrappedArchive(zip, wrapperDirectory, target);
            return target;
        } catch (IOException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith("Skill 压缩包")) {
                throw exception;
            }
            throw new IOException("无法读取 Skill 压缩包：" + source, exception);
        }
    }

    /**
     * 兼容 macOS Finder 常见的“压缩整个目录”格式：忽略 __MACOSX 后仅存在一个顶层目录，
     * 且该目录内直接包含 SKILL.md。豆包要求 SKILL.md 位于上传包根目录，因此生成临时规范包。
     */
    private static String findSingleSkillWrapper(ZipFile zip) {
        Set<String> topLevelNames = new LinkedHashSet<>();
        zip.stream()
                .map(ZipEntry::getName)
                .filter(name -> !isMacMetadata(name))
                .forEach(name -> {
                    int separator = name.indexOf('/');
                    if (separator > 0) {
                        topLevelNames.add(name.substring(0, separator));
                    } else if (!name.isBlank()) {
                        topLevelNames.add(name);
                    }
                });
        if (topLevelNames.size() != 1) {
            return null;
        }
        String wrapper = topLevelNames.iterator().next();
        ZipEntry skillMarkdown = zip.getEntry(wrapper + "/SKILL.md");
        return skillMarkdown != null && !skillMarkdown.isDirectory() ? wrapper : null;
    }

    private static void normalizeWrappedArchive(ZipFile source, String wrapperDirectory, Path target) throws IOException {
        String prefix = wrapperDirectory + "/";
        try (ZipOutputStream normalized = new ZipOutputStream(Files.newOutputStream(target))) {
            for (ZipEntry entry : source.stream().toList()) {
                String sourceName = entry.getName();
                if (!sourceName.startsWith(prefix) || isMacMetadata(sourceName)) {
                    continue;
                }
                String normalizedName = sourceName.substring(prefix.length());
                if (normalizedName.isEmpty()) {
                    continue;
                }
                if (!isSafeArchiveEntry(normalizedName)) {
                    throw new IOException("Skill 压缩包包含非法路径：" + sourceName);
                }
                normalized.putNextEntry(new ZipEntry(normalizedName));
                if (!entry.isDirectory()) {
                    try (var input = source.getInputStream(entry)) {
                        input.transferTo(normalized);
                    }
                }
                normalized.closeEntry();
            }
        }
    }

    private static boolean isMacMetadata(String entryName) {
        return entryName.startsWith("__MACOSX/")
                || entryName.equals("__MACOSX")
                || entryName.substring(entryName.lastIndexOf('/') + 1).startsWith("._");
    }

    private static boolean isSafeArchiveEntry(String entryName) {
        if (entryName.startsWith("/") || entryName.contains("\\")) {
            return false;
        }
        for (String segment : entryName.split("/")) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
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
