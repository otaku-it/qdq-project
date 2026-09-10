package com.zhiling.launcher.adapter;

import java.util.List;

/**
 * 将租户级 Agent Skills 上传到豆包企业工作台的外部适配器。
 *
 * <p>任务编排层只依赖该接口，不持有浏览器 Cookie 或登录凭据。真实实现通过独立
 * Playwright Worker 使用用户已登录的浏览器会话，避免敏感会话进入任务数据。</p>
 */
public interface AgentSkillUploadAdapter {

    /**
     * 上传本次管理员勾选的全部 Skills。
     *
     * @param request 租户、操作人和 Skill ID 列表
     * @return 可审计的上传结果；失败结果不会写入租户 Skills 目录
     */
    UploadResult upload(UploadRequest request);

    /** 上传请求，不包含浏览器凭据。 */
    record UploadRequest(String tenantName, String operatorName, List<String> skillIds) {
        public UploadRequest {
            skillIds = List.copyOf(skillIds);
        }
    }

    /** Worker 返回的结构化执行结果。 */
    record UploadResult(boolean success, boolean requiresUserAction, String mode, String message, List<String> uploadedSkillIds) {
        public UploadResult {
            uploadedSkillIds = uploadedSkillIds == null ? List.of() : List.copyOf(uploadedSkillIds);
        }

        public static UploadResult simulated(List<String> skillIds) {
            return new UploadResult(true, false, "SIMULATED", "Demo 模式：已模拟豆包企业 Skills 上传", skillIds);
        }

        public static UploadResult failed(String mode, String message) {
            return new UploadResult(false, false, mode, message, List.of());
        }

        public static UploadResult waitingForLogin(String mode, String message) {
            return new UploadResult(false, true, mode, message, List.of());
        }
    }
}
