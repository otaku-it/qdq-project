package com.zhiling.launcher.config;

import com.zhiling.launcher.service.AgentSkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/** 首次部署到空库时，将工程 Skills 目录中的既有能力登记为公共目录。 */
@Component
public class AgentSkillCatalogInitializer implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(AgentSkillCatalogInitializer.class);
    private final AgentSkillService service;
    public AgentSkillCatalogInitializer(AgentSkillService service) { this.service = service; }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (!service.listPublic().isEmpty()) return;
        } catch (RuntimeException e) {
            LOG.warn("Agent Skills 公共目录暂不可用，保留启动器内置目录：{}", e.getMessage());
            return;
        }
        List<Seed> seeds = List.of(
                new Seed("project-plan", "项目计划", "拆分里程碑、排期和负责人", "项目管理"),
                new Seed("requirement-analysis", "需求梳理", "提炼需求、验收标准和待确认项", "项目管理"),
                new Seed("risk-tracking", "风险跟踪", "识别风险、责任人和缓解动作", "项目管理"),
                new Seed("weekly-report", "周报生成", "汇总进展并生成项目周报", "协作效率"),
                new Seed("scene-analysis-enhanced", "买家秀场景分析", "分析买家实拍场景、风格和核心痛点，生成五 Sheet 分析表", "电商运营")
        );
        for (Seed seed : seeds) {
            try { service.registerExisting(seed.code, seed.name, seed.description, seed.category); }
            catch (Exception e) { LOG.warn("登记内置 Skill {} 失败：{}", seed.code, e.getMessage()); }
        }
    }
    private record Seed(String code, String name, String description, String category) {}
}
