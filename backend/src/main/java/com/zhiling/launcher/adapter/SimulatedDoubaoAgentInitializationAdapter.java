package com.zhiling.launcher.adapter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 本地演示实现，不会在豆包工作台创建真实任务。 */
@Component
@ConditionalOnProperty(name = "launcher.agent-initialization.mode", havingValue = "mock", matchIfMissing = true)
public class SimulatedDoubaoAgentInitializationAdapter implements DoubaoAgentInitializationAdapter {

    @Override
    public InitializationResult initialize(InitializationRequest request) {
        return InitializationResult.simulated(request.skillIds());
    }
}
