package com.zhiling.launcher.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaywrightAgentSkillUploadAdapterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void copiesZipPackageWithoutDroppingSupportingFiles() throws Exception {
        Path skillDirectory = Files.createDirectory(temporaryDirectory.resolve("skills"));
        Path source = skillDirectory.resolve("project-plan.zip");
        createArchive(source, "SKILL.md", "metadata.json", "references/guide.md", "scripts/run.js");
        Path outputDirectory = Files.createDirectory(temporaryDirectory.resolve("output"));

        Path uploaded = Path.of(adapter(skillDirectory).packageSkills(List.of("project-plan"), outputDirectory)
                .get("project-plan"));

        assertThat(uploaded.getFileName().toString()).isEqualTo("project-plan.zip");
        try (ZipFile archive = new ZipFile(uploaded.toFile())) {
            assertThat(archive.getEntry("SKILL.md")).isNotNull();
            assertThat(archive.getEntry("metadata.json")).isNotNull();
            assertThat(archive.getEntry("references/guide.md")).isNotNull();
            assertThat(archive.getEntry("scripts/run.js")).isNotNull();
        }
    }

    @Test
    void acceptsSkillArchiveAndPreservesItsExtension() throws Exception {
        Path skillDirectory = Files.createDirectory(temporaryDirectory.resolve("skills"));
        createArchive(skillDirectory.resolve("risk-tracking.skill"), "SKILL.md", "scripts/check.js");
        Path outputDirectory = Files.createDirectory(temporaryDirectory.resolve("output"));

        Path uploaded = Path.of(adapter(skillDirectory).packageSkills(List.of("risk-tracking"), outputDirectory)
                .get("risk-tracking"));

        assertThat(uploaded.getFileName().toString()).isEqualTo("risk-tracking.skill");
        try (ZipFile archive = new ZipFile(uploaded.toFile())) {
            assertThat(archive.getEntry("scripts/check.js")).isNotNull();
        }
    }

    @Test
    void keepsExistingSkillMarkdownDirectoryBehavior() throws Exception {
        Path skillDirectory = Files.createDirectory(temporaryDirectory.resolve("skills"));
        Path sourceDirectory = Files.createDirectory(skillDirectory.resolve("weekly-report"));
        Files.writeString(sourceDirectory.resolve("SKILL.md"), "---\nname: weekly-report\n---\n", StandardCharsets.UTF_8);
        Path outputDirectory = Files.createDirectory(temporaryDirectory.resolve("output"));

        Path uploaded = Path.of(adapter(skillDirectory).packageSkills(List.of("weekly-report"), outputDirectory)
                .get("weekly-report"));

        assertThat(uploaded.getFileName().toString()).isEqualTo("weekly-report.skill");
        try (ZipFile archive = new ZipFile(uploaded.toFile())) {
            assertThat(archive.getEntry("SKILL.md")).isNotNull();
        }
    }

    @Test
    void normalizesSingleWrappedSkillDirectoryAndRemovesMacMetadata() throws Exception {
        Path skillDirectory = Files.createDirectory(temporaryDirectory.resolve("skills"));
        createArchive(skillDirectory.resolve("project-plan.zip"),
                "package/SKILL.md", "package/references/rule.md", "package/scripts/run.py",
                "__MACOSX/package/._SKILL.md");
        Path outputDirectory = Files.createDirectory(temporaryDirectory.resolve("output"));

        Path uploaded = Path.of(adapter(skillDirectory).packageSkills(List.of("project-plan"), outputDirectory)
                .get("project-plan"));

        try (ZipFile archive = new ZipFile(uploaded.toFile())) {
            assertThat(archive.getEntry("SKILL.md")).isNotNull();
            assertThat(archive.getEntry("references/rule.md")).isNotNull();
            assertThat(archive.getEntry("scripts/run.py")).isNotNull();
            assertThat(archive.getEntry("package/SKILL.md")).isNull();
            assertThat(archive.getEntry("__MACOSX/package/._SKILL.md")).isNull();
        }
    }

    @Test
    void rejectsArchiveWithoutRootOrSingleWrappedSkillMarkdown() throws Exception {
        Path skillDirectory = Files.createDirectory(temporaryDirectory.resolve("skills"));
        createArchive(skillDirectory.resolve("project-plan.zip"), "one/SKILL.md", "two/metadata.json");
        Path outputDirectory = Files.createDirectory(temporaryDirectory.resolve("output"));

        assertThatThrownBy(() -> adapter(skillDirectory)
                .packageSkills(List.of("project-plan"), outputDirectory))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("根目录缺少 SKILL.md");
    }

    private PlaywrightAgentSkillUploadAdapter adapter(Path skillDirectory) throws IOException {
        Path worker = temporaryDirectory.resolve("worker.mjs");
        if (!Files.exists(worker)) {
            Files.writeString(worker, "", StandardCharsets.UTF_8);
        }
        return new PlaywrightAgentSkillUploadAdapter(
                new ObjectMapper(), "node", worker.toString(), skillDirectory.toString(), 120
        );
    }

    private static void createArchive(Path archive, String... entries) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write(entry.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }
}
