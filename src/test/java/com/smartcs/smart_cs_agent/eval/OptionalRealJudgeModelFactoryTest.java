package com.smartcs.smart_cs_agent.eval;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OptionalRealJudgeModelFactory 的配置开关测试。
 *
 * 这里不真的创建 OpenAI 模型，因为那会依赖网络和 API Key。
 * 我们只验证“是否启用真实 Judge”“是否严格断言”“报告里展示什么模式”这些纯配置逻辑。
 */
class OptionalRealJudgeModelFactoryTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty(OptionalRealJudgeModelFactory.ENABLED_PROPERTY);
        System.clearProperty(OptionalRealJudgeModelFactory.ASSERT_EXPECTATION_PROPERTY);
        System.clearProperty(OptionalRealJudgeModelFactory.MODEL_NAME_PROPERTY);
    }

    @Test
    void shouldDisableRealJudgeWhenPropertyIsFalse() {
        System.setProperty(OptionalRealJudgeModelFactory.ENABLED_PROPERTY, "false");

        assertFalse(OptionalRealJudgeModelFactory.enabled());
        assertEquals("fake", OptionalRealJudgeModelFactory.judgeMode());
    }

    @Test
    void shouldEnableRealJudgeWhenPropertyIsTrue() {
        System.setProperty(OptionalRealJudgeModelFactory.ENABLED_PROPERTY, "true");

        assertTrue(OptionalRealJudgeModelFactory.enabled());
    }

    @Test
    void shouldNotAssertExpectationByDefaultForRealJudge() {
        System.setProperty(OptionalRealJudgeModelFactory.ASSERT_EXPECTATION_PROPERTY, "false");

        assertFalse(OptionalRealJudgeModelFactory.shouldAssertExpectation());
    }

    @Test
    void shouldExposeRealJudgeModelNameInJudgeMode() {
        System.setProperty(OptionalRealJudgeModelFactory.ENABLED_PROPERTY, "true");
        System.setProperty(OptionalRealJudgeModelFactory.MODEL_NAME_PROPERTY, "demo-judge-model");

        assertEquals("real:demo-judge-model", OptionalRealJudgeModelFactory.judgeMode());
    }
}
