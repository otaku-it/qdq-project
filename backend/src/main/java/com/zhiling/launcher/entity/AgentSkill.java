package com.zhiling.launcher.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 公共 Agent Skill 主表。 */
@Data
@TableName("zw_qdq_agent_skill")
public class AgentSkill {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String skillCode;
    private String displayName;
    private String description;
    /** 管理员为 Skill 配置的默认执行提示词，可在后续初始化任务中复用。 */
    private String defaultPrompt;
    private String category;
    private Long currentVersionId;
    private String status;
    private Long createBy;
    private Long updateBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String delFlag;
}
