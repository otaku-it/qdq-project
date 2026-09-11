package com.zhiling.launcher.adapter;

import java.util.List;

/**
 * 在普通用户的豆包工作台中创建任务并挂载企业 Skills 的外部适配器。
 *
 * <p>编排服务只传递业务标识和 Skill ID，不读取或保存豆包登录凭据。真实浏览器会话由
 * Playwright Worker 管理，后续如果豆包开放正式 API，也可以新增实现而不修改任务树。</p>
 */
public interface DoubaoAgentInitializationAdapter {

    /**
     * 为当前用户创建一条豆包工作任务并发送初始化消息。
     *
     * @param request 租户、用户、任务幂等键和需要挂载的 Skill ID
     * @return 初始化结果；需要重新登录时通过 requiresUserAction 暂停任务树
     */
    InitializationResult initialize(InitializationRequest request);

    /** 初始化请求，不包含 Cookie、Token 等浏览器凭据。 */
    record InitializationRequest(
            String tenantName,
            String operatorName,
            String larkUser,
            String idempotencyKey,
            List<String> skillIds
    ) {
        public InitializationRequest {
            skillIds = List.copyOf(skillIds);
        }
    }

    /** Worker 返回的结构化初始化结果。 */
    record InitializationResult(
            boolean success,
            boolean requiresUserAction,
            String mode,
            String message,
            List<String> initializedSkillIds,
            String taskUrl
    ) {
        public InitializationResult {
            initializedSkillIds = initializedSkillIds == null ? List.of() : List.copyOf(initializedSkillIds);
        }

        public static InitializationResult simulated(List<String> skillIds) {
            return new InitializationResult(
                    true,
                    false,
                    "SIMULATED",
                    "Demo 模式：已模拟创建豆包工作任务并挂载企业 Skills",
                    skillIds,
                    null
            );
        }

        public static InitializationResult failed(String mode, String message) {
            return new InitializationResult(false, false, mode, message, List.of(), null);
        }

        public static InitializationResult waitingForLogin(String mode, String message) {
            return new InitializationResult(false, true, mode, message, List.of(), null);
        }
    }
}
