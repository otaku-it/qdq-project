-- 公共 Agent Skill 的默认提示词：用于记录管理员配置的默认执行指令。
-- MySQL 8.0.29+ 支持 IF NOT EXISTS；如数据库版本较低，请在确认字段不存在后执行 ADD COLUMN 部分。
ALTER TABLE zw_qdq_agent_skill
    ADD COLUMN IF NOT EXISTS default_prompt TEXT NULL COMMENT '默认提示词，由管理员配置供 Skill 初始化或执行时复用'
    AFTER description;
