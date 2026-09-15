package com.zhiling.launcher.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhiling.launcher.entity.AgentSkill;
import com.zhiling.launcher.entity.AgentSkillVersion;
import com.zhiling.launcher.entity.TenantAgentSkill;
import com.zhiling.launcher.mapper.AgentSkillMapper;
import com.zhiling.launcher.mapper.AgentSkillVersionMapper;
import com.zhiling.launcher.mapper.TenantAgentSkillMapper;
import com.zhiling.launcher.api.LauncherViews;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 公共 Agent Skills 的目录、版本和租户预置状态管理。 */
@Service
public class AgentSkillService {
    private static final Set<String> FORMATS = Set.of("zip", "skill", "md");

    private final AgentSkillMapper skillMapper;
    private final AgentSkillVersionMapper versionMapper;
    private final TenantAgentSkillMapper tenantMapper;
    private final Path skillDirectory;

    public AgentSkillService(AgentSkillMapper skillMapper,
                             AgentSkillVersionMapper versionMapper,
                             TenantAgentSkillMapper tenantMapper,
                             @Value("${launcher.skill-upload.package-directory:skills}") String skillDirectory) {
        this.skillMapper = skillMapper;
        this.versionMapper = versionMapper;
        this.tenantMapper = tenantMapper;
        this.skillDirectory = resolveDirectory(skillDirectory);
    }

    /** 查询未删除且已启用的公共 Skill，供启动器和管理页使用。 */
    public List<AgentSkill> listPublic() {
        return skillMapper.selectList(Wrappers.<AgentSkill>lambdaQuery()
                .eq(AgentSkill::getDelFlag, "0")
                .eq(AgentSkill::getStatus, "ACTIVE")
                .orderByAsc(AgentSkill::getId));
    }

    /** 管理页需要展示启用和停用的 Skill，供管理员执行状态切换。 */
    public List<AgentSkill> listForManagement() {
        List<AgentSkill> skills = skillMapper.selectList(Wrappers.<AgentSkill>lambdaQuery()
                .ne(AgentSkill::getStatus, "DELETED")
                .orderByAsc(AgentSkill::getId));
        // 兼容旧版“停用即逻辑删除”的历史数据，允许管理员在新页面重新启用。
        skills.forEach(skill -> {
            if (!"0".equals(skill.getDelFlag())) {
                skill.setStatus("DISABLED");
            }
        });
        return skills;
    }

    /** 以启动器视图模型返回公共目录，避免控制层实体字段泄漏。 */
    public List<LauncherViews.SkillView> getBlueprintSkills() {
        return listPublic().stream().map(skill -> new LauncherViews.SkillView(
                skill.getSkillCode(), skill.getDisplayName(),
                skill.getDescription() == null ? "" : skill.getDescription(),
                skill.getCategory() == null ? "通用" : skill.getCategory())).toList();
    }

    /** 返回所选启用 Skill 的非空默认提示词，供普通用户初始化豆包 Agent 时逐项发送。 */
    public Map<String, String> getDefaultPrompts(List<String> skillCodes) {
        if (skillCodes == null || skillCodes.isEmpty()) {
            return Map.of();
        }
        Set<String> selected = Set.copyOf(skillCodes);
        Map<String, String> prompts = new LinkedHashMap<>();
        for (AgentSkill skill : listPublic()) {
            String prompt = skill.getDefaultPrompt();
            if (selected.contains(skill.getSkillCode()) && prompt != null && !prompt.isBlank()) {
                prompts.put(skill.getSkillCode(), prompt);
            }
        }
        return prompts;
    }

    /** 普通用户只可看到当前租户已成功预置的公共 Skill。 */
    public List<AgentSkill> listForTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return List.of();
        tenantId = normalizeTenantId(tenantId);
        List<TenantAgentSkill> rows = tenantMapper.selectList(Wrappers.<TenantAgentSkill>lambdaQuery()
                .eq(TenantAgentSkill::getTenantId, tenantId)
                .eq(TenantAgentSkill::getProvisioningStatus, "UPLOADED")
                .eq(TenantAgentSkill::getDelFlag, "0"));
        if (rows.isEmpty()) return List.of();
        Set<Long> ids = rows.stream().map(TenantAgentSkill::getSkillId).collect(java.util.stream.Collectors.toSet());
        return skillMapper.selectList(Wrappers.<AgentSkill>lambdaQuery()
                .in(AgentSkill::getId, ids).eq(AgentSkill::getDelFlag, "0")
                .eq(AgentSkill::getStatus, "ACTIVE").orderByAsc(AgentSkill::getId));
    }

    /** 校验 Skill 编码是否为公共目录中的有效 Skill。 */
    public boolean containsCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) return true;
        Long count = skillMapper.selectCount(Wrappers.<AgentSkill>lambdaQuery()
                .in(AgentSkill::getSkillCode, codes).eq(AgentSkill::getDelFlag, "0")
                .eq(AgentSkill::getStatus, "ACTIVE"));
        return count != null && count == codes.stream().distinct().count();
    }

    /** 查询租户是否已为全部 Skill 完成预置。 */
    public boolean arePreloaded(String tenantId, List<String> codes) {
        if (codes == null || codes.isEmpty()) return true;
        tenantId = normalizeTenantId(tenantId);
        List<AgentSkill> skills = listPublic();
        List<Long> ids = skills.stream().filter(s -> codes.contains(s.getSkillCode())).map(AgentSkill::getId).toList();
        if (ids.size() != codes.stream().distinct().count()) return false;
        Long count = tenantMapper.selectCount(Wrappers.<TenantAgentSkill>lambdaQuery()
                .eq(TenantAgentSkill::getTenantId, tenantId).in(TenantAgentSkill::getSkillId, ids)
                .eq(TenantAgentSkill::getProvisioningStatus, "UPLOADED").eq(TenantAgentSkill::getDelFlag, "0"));
        return count != null && count == ids.size();
    }

    /** 管理员真实上传文件并记录新版本，上传成功后标记租户预置完成。 */
    @Transactional
    public AgentSkill upload(String skillCode, String displayName, String description, String defaultPrompt, String category,
                             MultipartFile file, Long operatorId) {
        validateCode(skillCode);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择 Skill 文件");
        String format = extension(Objects.requireNonNullElse(file.getOriginalFilename(), ""));
        if (!FORMATS.contains(format)) throw new IllegalArgumentException("仅支持 .zip、.skill 或 SKILL.md 文件");
        try {
            validatePackage(file, format);
            Files.createDirectories(skillDirectory);
            AgentSkill skill = skillMapper.selectOne(Wrappers.<AgentSkill>lambdaQuery()
                    .eq(AgentSkill::getSkillCode, skillCode).last("LIMIT 1"));
            if (skill == null) {
                skill = new AgentSkill();
                skill.setSkillCode(skillCode); skill.setDisplayName(displayName); skill.setDescription(description); skill.setDefaultPrompt(defaultPrompt);
                skill.setCategory(category); skill.setStatus("ACTIVE"); skill.setDelFlag("0");
                skill.setCreateBy(operatorId); skill.setUpdateBy(operatorId); skillMapper.insert(skill);
            } else {
                skill.setDisplayName(displayName); skill.setDescription(description); skill.setDefaultPrompt(defaultPrompt); skill.setCategory(category);
                skill.setStatus("ACTIVE"); skill.setDelFlag("0"); skill.setUpdateBy(operatorId); skillMapper.updateById(skill);
            }
            Integer versionNo = versionMapper.selectList(Wrappers.<AgentSkillVersion>lambdaQuery()
                    .eq(AgentSkillVersion::getSkillId, skill.getId()).orderByDesc(AgentSkillVersion::getVersionNo)
                    .last("LIMIT 1")).stream().findFirst().map(v -> v.getVersionNo() + 1).orElse(1);
            String storedName = format.equals("md") ? "SKILL.md" : skillCode + "." + format;
            Path versionDirectory = skillDirectory.resolve(skillCode).resolve("v" + versionNo).normalize();
            Path target = versionDirectory.resolve(storedName).normalize();
            if (!target.startsWith(skillDirectory)) throw new IllegalArgumentException("非法存储路径");
            Files.createDirectories(versionDirectory);
            Path temp = Files.createTempFile(versionDirectory, "skill-", ".upload");
            try (InputStream input = file.getInputStream()) { Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING); }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            // 同时维护一个固定入口，兼容现有 Playwright 适配器按 skillCode 查找最新包的约定。
            Path compatibilityTarget = format.equals("md")
                    ? skillDirectory.resolve(skillCode).resolve("SKILL.md")
                    : skillDirectory.resolve(skillCode + "." + format);
            Files.deleteIfExists(skillDirectory.resolve(skillCode + ".zip"));
            Files.deleteIfExists(skillDirectory.resolve(skillCode + ".skill"));
            Files.deleteIfExists(skillDirectory.resolve(skillCode).resolve("SKILL.md"));
            Files.createDirectories(compatibilityTarget.getParent());
            Files.copy(target, compatibilityTarget, StandardCopyOption.REPLACE_EXISTING);
            AgentSkillVersion version = new AgentSkillVersion();
            version.setSkillId(skill.getId()); version.setVersionNo(versionNo); version.setSourceFilename(file.getOriginalFilename());
            version.setStoragePath(target.toString()); version.setPackageFormat(format); version.setFileSize(file.getSize());
            version.setCreateBy(operatorId); version.setUpdateBy(operatorId); version.setDelFlag("0"); versionMapper.insert(version);
            skill.setCurrentVersionId(version.getId()); skill.setUpdateBy(operatorId); skillMapper.updateById(skill);
            return skill;
        } catch (IOException e) {
            throw new IllegalStateException("Skill 文件保存失败：" + e.getMessage(), e);
        }
    }

    /** 空库首次启动时登记工程中已有的 Skill，不复制或改写原文件。 */
    @Transactional
    public void registerExisting(String code, String name, String description, String category) throws IOException {
        AgentSkill existing = skillMapper.selectOne(Wrappers.<AgentSkill>lambdaQuery()
                .eq(AgentSkill::getSkillCode, code).last("LIMIT 1"));
        if (existing != null) return;
        Path source = findExistingSource(code);
        if (source == null) return;
        AgentSkill skill = new AgentSkill();
        skill.setSkillCode(code); skill.setDisplayName(name); skill.setDescription(description); skill.setCategory(category);
        skill.setStatus("ACTIVE"); skill.setDelFlag("0"); skillMapper.insert(skill);
        AgentSkillVersion version = new AgentSkillVersion();
        version.setSkillId(skill.getId()); version.setVersionNo(1); version.setSourceFilename(source.getFileName().toString());
        version.setStoragePath(source.toString()); version.setPackageFormat(source.getFileName().toString().equals("SKILL.md") ? "md" : extension(source.getFileName().toString()));
        version.setFileSize(Files.size(source)); version.setDelFlag("0"); versionMapper.insert(version);
        skill.setCurrentVersionId(version.getId()); skillMapper.updateById(skill);
    }

    private Path findExistingSource(String code) {
        for (Path candidate : List.of(skillDirectory.resolve(code + ".zip"), skillDirectory.resolve(code + ".skill"),
                skillDirectory.resolve(code).resolve("SKILL.md"))) {
            if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize();
        }
        return null;
    }

    /** 将管理员上传成功的 Skill 写入租户预置状态，兼容既有启动器状态机。 */
    @Transactional
    public void markUploaded(String tenantId, List<String> codes, Long operatorId) {
        tenantId = normalizeTenantId(tenantId);
        List<AgentSkill> skills = listPublic().stream().filter(s -> codes.contains(s.getSkillCode())).toList();
        for (AgentSkill skill : skills) {
            AgentSkillVersion version = versionMapper.selectById(skill.getCurrentVersionId());
            if (version != null) markUploaded(tenantId, skill, version, operatorId);
        }
    }

    private void markUploaded(String tenantId, AgentSkill skill, AgentSkillVersion version, Long operatorId) {
        TenantAgentSkill row = tenantMapper.selectOne(Wrappers.<TenantAgentSkill>lambdaQuery()
                .eq(TenantAgentSkill::getTenantId, tenantId).eq(TenantAgentSkill::getSkillId, skill.getId()).last("LIMIT 1"));
        if (row == null) { row = new TenantAgentSkill(); row.setTenantId(tenantId); row.setSkillId(skill.getId()); row.setCreateBy(operatorId); row.setDelFlag("0"); }
        row.setSkillVersionId(version.getId()); row.setProvisioningStatus("UPLOADED"); row.setUploadedBy(operatorId);
        row.setUploadedTime(LocalDateTime.now()); row.setLastError(null); row.setUpdateBy(operatorId);
        if (row.getId() == null) tenantMapper.insert(row); else tenantMapper.updateById(row);
    }

    /** 逻辑删除公共 Skill，不删除已上传文件和历史版本。 */
    @Transactional
    public void disable(Long id, Long operatorId) {
        AgentSkill skill = skillMapper.selectById(id);
        if (skill == null) throw new IllegalArgumentException("Skill 不存在");
        skill.setStatus("DISABLED"); skill.setDelFlag("1"); skill.setUpdateBy(operatorId); skillMapper.updateById(skill);
    }

    /** 启用或停用公共 Skill；停用后不再出现在启动器可选目录中，但保留文件和版本数据。 */
    @Transactional
    public AgentSkill changeStatus(Long id, String status, Long operatorId) {
        if (!Set.of("ACTIVE", "DISABLED").contains(status)) {
            throw new IllegalArgumentException("不支持的 Skill 状态");
        }
        AgentSkill skill = skillMapper.selectById(id);
        if (skill == null || "DELETED".equals(skill.getStatus())) throw new IllegalArgumentException("Skill 不存在");
        skill.setStatus(status);
        skill.setDelFlag("0");
        skill.setUpdateBy(operatorId);
        skillMapper.updateById(skill);
        return skill;
    }

    /** 更新公共 Skill 的展示和默认提示词配置，不改动编码、文件或版本。 */
    @Transactional
    public AgentSkill updateMetadata(Long id, String displayName, String description,
                                     String defaultPrompt, String category, Long operatorId) {
        AgentSkill skill = skillMapper.selectById(id);
        if (skill == null || "DELETED".equals(skill.getStatus())) {
            throw new IllegalArgumentException("Skill 不存在");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("中文名称不能为空");
        }
        skill.setDisplayName(displayName.trim());
        skill.setDescription(description == null ? "" : description.trim());
        skill.setDefaultPrompt(defaultPrompt == null ? "" : defaultPrompt.trim());
        skill.setCategory(category == null || category.isBlank() ? "通用" : category.trim());
        skill.setUpdateBy(operatorId);
        skillMapper.updateById(skill);
        return skill;
    }

    /** 逻辑删除公共 Skill；文件与历史版本保留，避免误删后无法审计或恢复。 */
    @Transactional
    public void delete(Long id, Long operatorId) {
        AgentSkill skill = skillMapper.selectById(id);
        if (skill == null || "DELETED".equals(skill.getStatus())) {
            throw new IllegalArgumentException("Skill 不存在");
        }
        skill.setStatus("DELETED");
        skill.setDelFlag("1");
        skill.setUpdateBy(operatorId);
        skillMapper.updateById(skill);
    }

    private void validatePackage(MultipartFile file, String format) throws IOException {
        if (!format.equals("zip") && !format.equals("skill")) {
            String name = file.getOriginalFilename();
            if (name == null || !name.equalsIgnoreCase("SKILL.md")) throw new IllegalArgumentException("单文件上传必须命名为 SKILL.md");
            return;
        }
        Path temp = Files.createTempFile("skill-check-", "." + format);
        try (InputStream input = file.getInputStream()) { Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING); }
        try (ZipFile zip = new ZipFile(temp.toFile())) {
            boolean root = zip.getEntry("SKILL.md") != null;
            boolean wrapped = zip.stream().anyMatch(e -> e.getName().matches("[^/]+/SKILL\\.md"));
            if (!root && !wrapped) throw new IllegalArgumentException("Skill 压缩包根目录或单层目录必须包含 SKILL.md");
        } finally { Files.deleteIfExists(temp); }
    }

    private static void validateCode(String code) {
        if (code == null || !code.matches("[a-z0-9-]{2,64}")) throw new IllegalArgumentException("Skill 编码仅支持小写字母、数字和短横线");
    }
    private static String extension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip")) return "zip";
        if (lower.endsWith(".skill")) return "skill";
        if (lower.endsWith("skill.md") || lower.endsWith("/skill.md")) return "md";
        return "";
    }
    private static Path resolveDirectory(String configured) {
        Path direct = Path.of(configured).toAbsolutePath().normalize();
        if (Files.exists(direct)) return direct;
        return Path.of("backend").resolve(configured).toAbsolutePath().normalize();
    }

    private static String normalizeTenantId(String tenantId) {
        if (tenantId == null) return "";
        String value = tenantId.trim();
        return value.length() > 20 ? value.substring(0, 20) : value;
    }
}
