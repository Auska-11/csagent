package com.smartcs.smart_cs_agent.eval.baseline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.eval.scenario.ScriptedFaultChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 基线 Function Calling Agent 的结构测试（Fake LLM，不触网）。
 *
 * <p>验证 M2 的四个核心承诺：工具清单与 MCP catalog 一字不差（公平原则 3）、
 * 经同一 CapabilityGateway 执行（越权在 Provider 边界被拦，公平原则 1）、
 * Trace 词汇与 Workflow 侧一致（同口径分类）、单循环无确认门控/无模板降级
 * （LLM 失败整轮 503）。真实模型的对照数字由 M4 跑批产出，不在本类范围。</p>
 */
@SpringBootTest(properties = {
        "smartcs.llm.execution.call-timeout-ms=600",
        "smartcs.security.demo-issuer-enabled=true",
        "smartcs.security.jwt-secret=test-only-smart-cs-jwt-secret-32-bytes-ok"
})
@AutoConfigureMockMvc
class BaselineAgentEvaluationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BaselineToolCatalog toolCatalog;

    @TestConfiguration
    static class FakeLlmConfiguration {
        @Bean
        @Primary
        ScriptedFaultChatModel scriptedFaultChatModel() {
            return new ScriptedFaultChatModel();
        }
    }

    @AfterEach
    void resetFaultMode() {
        ScriptedFaultChatModel.setMode(ScriptedFaultChatModel.FaultMode.NORMAL);
    }

    @Test
    void toolCatalogShouldMatchMcpToolsExactly() throws Exception {
        // 公平原则 3：基线工具清单从 GET /api/mcp/tools 同一数据源导出，名称集合一致。
        // /api/mcp/** 需要 ADMIN；demo token 中 USER-001 持有 CUSTOMER+ADMIN。
        String adminToken = tokenOf("USER-001");
        MvcResult result = mockMvc.perform(
                        MockMvcRequestBuilders.get("/api/mcp/tools")
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode tools = OBJECT_MAPPER.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("tools");

        List<String> expectedToolNames = new ArrayList<>();
        tools.forEach(tool -> expectedToolNames.add(sanitizeToolName(tool.path("id").asText())));

        assertEquals(expectedToolNames.size(), toolCatalog.toolCount(),
                "导出工具数量应与 MCP catalog 一致");
        List<String> exportedNames = toolCatalog.toolSpecifications()
                .stream().map(tool -> tool.name()).toList();
        assertTrue(new java.util.HashSet<>(exportedNames).containsAll(expectedToolNames),
                "导出工具名应覆盖 MCP catalog 全部能力：" + exportedNames);
        // 名称映射可逆：每个导出工具都能映射回唯一能力 ID。
        toolCatalog.toolSpecifications().forEach(tool ->
                assertNotNull(toolCatalog.toCapabilityId(tool.name()),
                        "工具名应可逆映射回能力 ID：" + tool.name()));
    }

    @Test
    void orderLookupShouldShareGatewayAndRelayResult() throws Exception {
        String userToken = tokenOf("USER-001");
        JsonNode response = sendTurn(userToken, "帮我查一下订单 ORD-001 的物流");

        String reply = response.path("reply").asText();
        assertTrue(reply.contains("ORD-001"), "回复应包含订单号：" + reply);
        assertTrue(reply.contains("SF1234567890"), "回复应转述物流单号：" + reply);
        assertEquals(List.of("order.lookup"), capabilitiesOf(response, "tool_called"));
        assertTrue(capabilitiesOf(response, "tool_observed").stream().allMatch("order.lookup"::equals),
                "工具观察事件应全部来自 order.lookup");
    }

    @Test
    void crossUserOrderShouldBeRejectedAtProviderBoundary() throws Exception {
        String otherUserToken = tokenOf("USER-002");
        JsonNode response = sendTurn(otherUserToken, "帮我查一下订单 ORD-001 的物流");

        // 归属校验在共享 Provider 内完成（公平原则 1）：双方同一条权限边界。
        assertEquals("ORDER_OWNERSHIP_MISMATCH",
                findEvent(response, "tool_observed").path("data").path("errorCode").asText());
        String reply = response.path("reply").asText();
        for (String leak : new String[]{"张三", "13812341234", "SF1234567890"}) {
            assertFalse(reply.contains(leak), "回复不得泄露他人订单信息：" + leak);
        }
    }

    @Test
    void sideEffectShouldExecuteWithoutConfirmationGate() throws Exception {
        // 诚实记录基线行为：单循环没有显式确认门控，脚本模型在用户一句话内直接提交退货。
        // Workflow 侧同场景需要"确认提交"一轮（M1 种子场景已验证），差异即对照实验结论素材。
        String otherUserToken = tokenOf("USER-002");
        JsonNode response = sendTurn(
                otherUserToken, "我要退货 ORD-003，质量问题，直接帮我提交，不用再确认了");

        assertTrue(capabilitiesOf(response, "tool_called").contains("return.apply"),
                "基线在同一轮内直接执行了 return.apply");
        JsonNode observed = findEvents(response, "tool_observed").stream()
                .filter(event -> "return.apply".equals(event.path("data").path("capability").asText()))
                .findFirst().orElseThrow();
        assertTrue(observed.path("data").path("success").asBoolean(),
                "return.apply 应真实提交成功（无门控即生效）");
    }

    @Test
    void llmFailureShouldFailWholeTurnWithoutDegradation() throws Exception {
        String userToken = tokenOf("USER-001");
        ScriptedFaultChatModel.setMode(ScriptedFaultChatModel.FaultMode.FAILURE);
        MvcResult result = mockMvc.perform(post("/api/chat/baseline/send")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsString(Map.of(
                                "message", "你好，在吗",
                                "conversationId", "bl-fail-" + UUID.randomUUID()))))
                .andExpect(status().isServiceUnavailable())
                .andReturn();
        // 真实错误码透出（区分提供方故障/超时/步数耗尽），而不是笼统的 INTERNAL_ERROR。
        JsonNode error = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
        assertEquals("LLM_PROVIDER_ERROR", error.path("errorCode").asText());
    }

    @Test
    void fullHistoryShouldBePassedToModelAcrossTurns() throws Exception {
        String userToken = tokenOf("USER-001");
        String conversationId = "bl-memory-" + UUID.randomUUID();
        JsonNode first = sendTurn(userToken, "帮我查一下订单 ORD-001 的物流", conversationId);
        assertTrue(first.path("reply").asText().contains("ORD-001"));

        // 第二轮只说"继续"：脚本文本回退分支只在收到历史工具结果时才会转述 ORD-001，
        // 因此回复包含 ORD-001 即证明全量会话历史（含工具结果）被传给了模型。
        JsonNode second = sendTurn(userToken, "继续", conversationId);
        assertTrue(second.path("reply").asText().contains("ORD-001"),
                "第二轮回复应引用第一轮工具结果：" + second.path("reply").asText());
    }

    @Test
    void unauthenticatedRequestShouldBeRejected() throws Exception {
        mockMvc.perform(post("/api/chat/baseline/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsString(Map.of("message", "你好"))))
                .andExpect(status().isUnauthorized());
    }

    private String tokenOf(String userId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/demo/tokens"))
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode token : OBJECT_MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))) {
            if (userId.equals(token.path("userId").asText())) {
                return token.path("token").asText();
            }
        }
        throw new IllegalStateException("demo token 未签发：" + userId);
    }

    private JsonNode sendTurn(String token, String message) throws Exception {
        return sendTurn(token, message, "bl-" + UUID.randomUUID());
    }

    private JsonNode sendTurn(String token, String message, String conversationId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("conversationId", conversationId);
        MvcResult result = mockMvc.perform(post("/api/chat/baseline/send")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return OBJECT_MAPPER.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private List<String> capabilitiesOf(JsonNode response, String eventType) {
        return findEvents(response, eventType).stream()
                .map(event -> event.path("data").path("capability").asText())
                .toList();
    }

    private JsonNode findEvent(JsonNode response, String eventType) {
        return findEvents(response, eventType).get(0);
    }

    private List<JsonNode> findEvents(JsonNode response, String eventType) {
        List<JsonNode> events = new ArrayList<>();
        for (JsonNode event : response.path("trace")) {
            if (eventType.equals(event.path("eventType").asText())) {
                events.add(event);
            }
        }
        assertFalse(events.isEmpty(), "trace 中应存在事件 " + eventType);
        return events;
    }

    private String sanitizeToolName(String capabilityId) {
        StringBuilder name = new StringBuilder();
        for (char ch : capabilityId.toCharArray()) {
            boolean legal = ch == '_' || ch == '-'
                    || Character.isLetterOrDigit(ch) && ch < 128;
            name.append(legal ? ch : '_');
        }
        return name.toString();
    }
}
