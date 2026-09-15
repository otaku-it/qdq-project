package com.zhiling.launcher.api;

import com.zhiling.launcher.api.AgentSkillViews.ListResponse;
import com.zhiling.launcher.api.AgentSkillViews.SkillView;
import com.zhiling.launcher.api.AgentSkillViews.UpdateRequest;
import com.zhiling.launcher.service.AgentSkillService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** 公共 Agent Skills 管理 API。真实项目应将 role/operatorId 替换为认证上下文。 */
@RestController
@RequestMapping("/api/v1/agent-skills")
public class AgentSkillController {
    private final AgentSkillService service;
    public AgentSkillController(AgentSkillService service) { this.service = service; }

    @GetMapping
    public ListResponse list(@RequestParam(defaultValue = "ADMIN") String role,
                             @RequestParam(required = false) String tenantId) {
        List<SkillView> skills = "USER".equalsIgnoreCase(role)
                ? service.listForTenant(tenantId).stream().map(SkillView::from).toList()
                : service.listForManagement().stream().map(SkillView::from).toList();
        return new ListResponse(skills);
    }

    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public SkillView upload(@RequestParam String role,
                            @RequestParam(required = false) String tenantId,
                            @RequestParam(required = false) Long operatorId,
                            @RequestParam String skillCode,
                            @RequestParam String displayName,
                            @RequestParam(required = false, defaultValue = "") String description,
                            @RequestParam(required = false, defaultValue = "") String defaultPrompt,
                            @RequestParam(required = false, defaultValue = "通用") String category,
                            @RequestPart("file") MultipartFile file,
                            @RequestHeader(value = "X-Role", required = false) String roleHeader) {
        requireAdmin(roleHeader == null || roleHeader.isBlank() ? role : roleHeader);
        return SkillView.from(service.upload(skillCode.trim(), displayName.trim(), description.trim(), defaultPrompt.trim(), category.trim(), file, operatorId));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id,
                        @RequestParam String role,
                        @RequestParam(required = false) Long operatorId,
                        @RequestHeader(value = "X-Role", required = false) String roleHeader) {
        requireAdmin(roleHeader == null || roleHeader.isBlank() ? role : roleHeader);
        try {
            service.delete(id, operatorId);
        } catch (IllegalArgumentException e) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    /** 编辑公共 Skill 的展示元数据和默认提示词。 */
    @PutMapping("/{id}")
    public SkillView update(@PathVariable Long id,
                            @RequestBody UpdateRequest request,
                            @RequestParam String role,
                            @RequestParam(required = false) Long operatorId,
                            @RequestHeader(value = "X-Role", required = false) String roleHeader) {
        requireAdmin(roleHeader == null || roleHeader.isBlank() ? role : roleHeader);
        if (request == null || request.displayName() == null || request.displayName().isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "中文名称不能为空");
        }
        try {
            return SkillView.from(service.updateMetadata(id, request.displayName(), request.description(),
                    request.defaultPrompt(), request.category(), operatorId));
        } catch (IllegalArgumentException e) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    /** 管理员切换公共 Skill 的启用状态，不删除 Skill 文件或版本记录。 */
    @PatchMapping("/{id}/status")
    public SkillView changeStatus(@PathVariable Long id,
                                  @RequestParam String status,
                                  @RequestParam String role,
                                  @RequestParam(required = false) Long operatorId,
                                  @RequestHeader(value = "X-Role", required = false) String roleHeader) {
        requireAdmin(roleHeader == null || roleHeader.isBlank() ? role : roleHeader);
        String normalizedStatus = status.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"ACTIVE".equals(normalizedStatus) && !"DISABLED".equals(normalizedStatus)) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "状态仅支持 ACTIVE 或 DISABLED");
        }
        try {
            return SkillView.from(service.changeStatus(id, normalizedStatus, operatorId));
        } catch (IllegalArgumentException e) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    private void requireAdmin(String role) {
        if (!"ADMIN".equalsIgnoreCase(role)) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN, "仅企业管理员可管理 Agent Skills");
    }
}
