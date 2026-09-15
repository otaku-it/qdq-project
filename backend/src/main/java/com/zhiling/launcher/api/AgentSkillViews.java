package com.zhiling.launcher.api;

import com.zhiling.launcher.entity.AgentSkill;

import java.util.List;

/** Agent Skills 管理接口返回模型。 */
public final class AgentSkillViews {
    private AgentSkillViews() {}
    public record SkillView(Long id, String skillCode, String displayName, String description,
                            String defaultPrompt, String category, Long currentVersionId, String status) {
        public static SkillView from(AgentSkill skill) {
            return new SkillView(skill.getId(), skill.getSkillCode(), skill.getDisplayName(), skill.getDescription(),
                    skill.getDefaultPrompt(), skill.getCategory(), skill.getCurrentVersionId(), skill.getStatus());
        }
    }
    public record ListResponse(List<SkillView> skills) {}

    /** 编辑公共 Skill 时可修改的元数据，编码和文件版本由上传流程维护。 */
    public record UpdateRequest(String displayName, String description, String defaultPrompt, String category) {}
}
