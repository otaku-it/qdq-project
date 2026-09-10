package com.zhiling.launcher.adapter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本地演示适配器，只验证编排流程，不产生豆包工作台外部副作用。
 */
@Component
@ConditionalOnProperty(name = "launcher.skill-upload.mode", havingValue = "mock", matchIfMissing = true)
public class SimulatedAgentSkillUploadAdapter implements AgentSkillUploadAdapter {

    @Override
    public UploadResult upload(UploadRequest request) {
        return UploadResult.simulated(request.skillIds());
    }
}
