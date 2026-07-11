package com.smartcs.smart_cs_agent.workflow.config;

import com.smartcs.smart_cs_agent.skill.CustomerServiceSkill;
import com.smartcs.smart_cs_agent.workflow.api.AgentWorkflow;
import com.smartcs.smart_cs_agent.workflow.legacy.LegacySkillWorkflowAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 迁移期间把旧 CustomerServiceSkill 暴露成一个真正的 Spring AgentWorkflow Bean。
 *
 * <p>当订单、商品、知识问答等独立 Workflow 全部迁移完后，可以删除这个 Bean；
 * WorkflowRegistry 和 Router 不需要因此修改。</p>
 */
@Configuration
public class WorkflowBeanConfiguration {

    @Bean
    public AgentWorkflow legacyCustomerServiceWorkflow(CustomerServiceSkill customerServiceSkill) {
        return new LegacySkillWorkflowAdapter(customerServiceSkill);
    }
}
