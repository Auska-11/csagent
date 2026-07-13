package com.smartcs.smart_cs_agent.architecture;

import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.workflow.core.WorkflowRouteResult;
import com.smartcs.smart_cs_agent.workflow.core.WorkflowRouter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Phase 9 路由行为基线。
 *
 * <p>兼容层清理最容易出现两类回归：业务请求被通用客服抢走，或者删掉旧 Skill 后闲聊和模糊
 * 输入没有任何 Workflow 接管。本测试使用 Spring 实际装配的全部 Workflow，只执行路由而不调用
 * 外部 LLM 和工具，因此可以稳定验证生产注册关系。</p>
 */
@SpringBootTest
class Phase9WorkflowRoutingBaselineTest {

    private static final String GENERAL_WORKFLOW_ID = "customer_service";

    @Autowired
    private WorkflowRouter workflowRouter;

    @Test
    void businessRequestsShouldRemainOwnedBySpecializedWorkflows() {
        Map<String, String> expectedRoutes = new LinkedHashMap<>();
        expectedRoutes.put("查询订单 ORD-001", "order_query");
        expectedRoutes.put("蓝牙耳机多少钱，有货吗", "product_advisory");
        expectedRoutes.put("我要退货，订单号 ORD-004", "return_apply");
        expectedRoutes.put("查询退货进度 RET-001", "return_query");
        expectedRoutes.put("超过七天还能退吗", "policy_qa");
        expectedRoutes.put("查看我的个人信息", "user_profile");
        expectedRoutes.put("我要投诉并转人工", "escalation");
        expectedRoutes.put("蓝牙耳机保修多久", "warranty_query");

        expectedRoutes.forEach((message, expectedWorkflowId) -> {
            WorkflowRouteResult result = routeFreshConversation(message);
            String actualWorkflowId = result.getSelectedWorkflow().descriptor().getId();

            assertEquals(expectedWorkflowId, actualWorkflowId, "消息路由错误：" + message);
            assertNotEquals(GENERAL_WORKFLOW_ID, actualWorkflowId, "明确业务请求不应退回通用兜底");
        });
    }

    @Test
    void chitchatAndAmbiguousRequestsShouldKeepAStableGeneralOwner() {
        for (String message : List.of("你好", "谢谢", "我有个问题")) {
            WorkflowRouteResult result = routeFreshConversation(message);

            // Phase 9.2 会替换具体实现，但稳定 Workflow ID 必须保持不变，
            // 否则 customer-service Skill Package 的 manifest 绑定和会话恢复都会失效。
            assertEquals(
                    GENERAL_WORKFLOW_ID,
                    result.getSelectedWorkflow().descriptor().getId(),
                    "闲聊或模糊输入必须继续由通用客服 Workflow 接管：" + message
            );
        }
    }

    private WorkflowRouteResult routeFreshConversation(String message) {
        ChatRequest request = new ChatRequest("phase9-route-baseline", "USER-PHASE9", message);
        ConversationState state = new ConversationState(message, "USER-PHASE9");
        return workflowRouter.route(request, state);
    }
}
