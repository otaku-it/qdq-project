package com.zhiling.launcher.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 公共 Agent Skill 文件版本表。 */
@Data
@TableName("zw_qdq_agent_skill_version")
public class AgentSkillVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long skillId;
    private Integer versionNo;
    private String sourceFilename;
    private String storagePath;
    private String packageFormat;
    private Long fileSize;
    private Long createBy;
    private Long updateBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String delFlag;
}
