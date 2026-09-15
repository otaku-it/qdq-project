package com.zhiling.launcher.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 租户将公共 Skill 预置到豆包后的状态表。 */
@Data
@TableName("zw_qdq_tenant_agent_skill")
public class TenantAgentSkill {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long skillId;
    private Long skillVersionId;
    private String provisioningStatus;
    private String doubaoSkillId;
    private String doubaoSkillName;
    private Long uploadedBy;
    private LocalDateTime uploadedTime;
    private String lastError;
    private Long createBy;
    private Long updateBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String delFlag;
}
