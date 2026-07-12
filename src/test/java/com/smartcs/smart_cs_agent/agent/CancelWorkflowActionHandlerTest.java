package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.action.CancelWorkflowAction;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionExecutionResult;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionFlow;
import com.smartcs.smart_cs_agent.agent.handler.defaults.CancelWorkflowActionHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** CancelWorkflowAction 的小型契约测试：取消必须是独立控制流，不能伪装成普通完成。 */
class CancelWorkflowActionHandlerTest {

    @Test
    void shouldReturnCancelledFlowWithUserFacingAnswer() {
        CancelWorkflowActionHandler handler = new CancelWorkflowActionHandler();
        CancelWorkflowAction action = new CancelWorkflowAction("本次操作已取消。");

        ActionExecutionResult result = handler.handle(action, null);

        assertEquals(ActionFlow.CANCELLED, result.getFlow());
        assertEquals("本次操作已取消。", result.getAnswer());
    }

    @Test
    void shouldRejectBlankCancellationAnswer() {
        assertThrows(IllegalArgumentException.class, () -> new CancelWorkflowAction("  "));
    }
}
