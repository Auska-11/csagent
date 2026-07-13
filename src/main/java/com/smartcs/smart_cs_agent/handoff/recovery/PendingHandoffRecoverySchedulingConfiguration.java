package com.smartcs.smart_cs_agent.handoff.recovery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 只有显式开启恢复功能时才启动 Spring 定时调度线程。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "smartcs.handoff.recovery.enabled", havingValue = "true")
public class PendingHandoffRecoverySchedulingConfiguration {
}
