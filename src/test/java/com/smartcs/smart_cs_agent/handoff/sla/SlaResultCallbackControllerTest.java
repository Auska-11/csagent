package com.smartcs.smart_cs_agent.handoff.sla;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import static com.smartcs.smart_cs_agent.handoff.sla.SlaResultCallbackFixtures.association;
import static com.smartcs.smart_cs_agent.handoff.sla.SlaResultCallbackFixtures.payload;
import static com.smartcs.smart_cs_agent.handoff.sla.SlaResultCallbackFixtures.withContent;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 回调接收端的协议外壳：未启用 404、令牌 401、格式 400、租户绑定 403、成功返回
 * SLA 确认协议要求的 {"success":true,"data":{"eventId":...,"disposition":...}}。
 *
 * <p>会落库的用例使用真实 JDBC Store（H2/MySQL 模式）：持久化护栏禁止内存存储向
 * SLA 返回成功确认，因此 200/409/404 等到达存储的路径必须在持久化形态下验证。</p>
 */
@SpringBootTest(properties = {
        "smartcs.persistence.mode=jdbc",
        "smartcs.persistence.jdbc.driver-class-name=org.h2.Driver",
        "smartcs.persistence.jdbc.url=jdbc:h2:mem:smartcs_sla_callback;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "smartcs.persistence.jdbc.username=sa",
        "smartcs.persistence.jdbc.password=",
        "smartcs.persistence.jdbc.connect-timeout-ms=1000",
        "smartcs.persistence.jdbc.socket-timeout-ms=2000",
        "smartcs.persistence.jdbc.query-timeout-seconds=2",
        "smartcs.persistence.jdbc.pool-max-size=4"
})
class SlaResultCallbackControllerTest {

    private static final String CALLBACK_TOKEN = "callback-token-1";

    @Autowired
    private JdbcSlaIntegrationStore jdbcStore;

    @Autowired
    private ObjectMapper objectMapper;

    private SlaV1Properties enabledProperties() {
        SlaV1Properties properties = new SlaV1Properties();
        properties.setCallbackToken(CALLBACK_TOKEN);
        properties.setCallbackTenantId("tenant-a");
        return properties;
    }

    private SlaResultCallbackController controller(SlaV1Properties properties,
                                                   SlaIntegrationStore store) {
        return new SlaResultCallbackController(properties, store, objectMapper);
    }

    private String uniqueRequestId() {
        return "wi-v1-" + java.util.UUID.randomUUID().toString().replace("-", "")
                + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 27);
    }

    @Test
    void shouldAnswerNotFoundWhenCallbackIsNotConfigured() {
        SlaV1Properties disabled = new SlaV1Properties();

        ResponseEntity<SlaResultCallbackController.SlaCallbackApiResponse> response =
                controller(disabled, jdbcStore)
                        .receive("Bearer " + CALLBACK_TOKEN, validBody());

        assertEquals(404, response.getStatusCode().value());
        assertEquals(false, response.getBody().success());
        assertEquals("NOT_FOUND", response.getBody().message());
    }

    @Test
    void shouldRejectMissingOrWrongToken() {
        SlaResultCallbackController callback = controller(enabledProperties(), jdbcStore);

        assertEquals(401, callback.receive(null, validBody()).getStatusCode().value());
        assertEquals(401, callback.receive("Basic abc", validBody()).getStatusCode().value());
        assertEquals(401, callback.receive("Bearer wrong-token", validBody()).getStatusCode().value());
        assertEquals(
                "UNAUTHORIZED",
                callback.receive("Bearer wrong-token", validBody()).getBody().message()
        );
    }

    @Test
    void shouldRejectMalformedJsonAndInvalidPayload() {
        SlaResultCallbackController callback = controller(enabledProperties(), jdbcStore);
        String bearer = "Bearer " + CALLBACK_TOKEN;

        assertEquals(400, callback.receive(bearer, "{not-json").getStatusCode().value());
        assertEquals(
                "INVALID_REQUEST",
                callback.receive(bearer, "{not-json").getBody().message()
        );
        // 契约外 schemaVersion / 缺 result：同样是 400，不进存储。
        assertEquals(400, callback.receive(bearer, "{\"schemaVersion\":2}").getStatusCode().value());
        assertEquals(400, callback.receive(bearer, "{}").getStatusCode().value());
    }

    @Test
    void shouldRejectTenantMismatchWithForbidden() {
        SlaResultCallbackController callback = controller(enabledProperties(), jdbcStore);
        String otherTenant = validBody().replace("\"tenant-a\"", "\"tenant-b\"");

        ResponseEntity<SlaResultCallbackController.SlaCallbackApiResponse> response =
                callback.receive("Bearer " + CALLBACK_TOKEN, otherTenant);

        assertEquals(403, response.getStatusCode().value());
        assertEquals("FORBIDDEN", response.getBody().message());
    }

    @Test
    void shouldRefuseToAcknowledgeWhenStoreIsMemoryOnly() {
        // 持久化护栏：鉴权、格式、租户都通过，但存储是内存实现——必须拒绝确认，
        // 否则 SLA 侧会认为结果已持久化，而 Agent 重启后结果就丢了。
        InMemorySlaIntegrationStore memoryStore = new InMemorySlaIntegrationStore();
        memoryStore.recordAssociation(association());
        SlaResultCallbackController callback = controller(enabledProperties(), memoryStore);

        ResponseEntity<SlaResultCallbackController.SlaCallbackApiResponse> response =
                callback.receive("Bearer " + CALLBACK_TOKEN, validBody());

        assertEquals(500, response.getStatusCode().value());
        assertEquals("PERSISTENCE_REQUIRED", response.getBody().message());
        // 不允许内存存储先吞掉事件：Inbox 必须保持为空，换到持久化部署后可重新接收。
        assertEquals(java.util.Optional.empty(), memoryStore.findCurrentResult(
                SlaResultCallbackFixtures.DEFAULT_REQUEST_ID));
    }

    @Test
    void shouldAcknowledgeAppliedAndDuplicateWithProtocolEnvelope() {
        String requestId = uniqueRequestId();
        jdbcStore.recordAssociation(association(requestId));
        SlaResultCallbackController callback = controller(enabledProperties(), jdbcStore);
        String bearer = "Bearer " + CALLBACK_TOKEN;
        String body = bodyOf(payload(requestId, "sla-event-ack-1", 1, "CONFIRMED"));

        ResponseEntity<SlaResultCallbackController.SlaCallbackApiResponse> applied =
                callback.receive(bearer, body);
        assertEquals(200, applied.getStatusCode().value());
        assertEquals(true, applied.getBody().success());
        SlaResultCallbackController.SlaCallbackAck appliedAck =
                (SlaResultCallbackController.SlaCallbackAck) applied.getBody().data();
        assertEquals("sla-event-ack-1", appliedAck.eventId());
        assertEquals("APPLIED", appliedAck.disposition());

        ResponseEntity<SlaResultCallbackController.SlaCallbackApiResponse> replay =
                callback.receive(bearer, body);
        assertEquals(200, replay.getStatusCode().value());
        SlaResultCallbackController.SlaCallbackAck replayAck =
                (SlaResultCallbackController.SlaCallbackAck) replay.getBody().data();
        assertEquals("sla-event-ack-1", replayAck.eventId());
        assertEquals("DUPLICATE", replayAck.disposition());
    }

    @Test
    void shouldPropagateProtocolRejections() {
        String requestId = uniqueRequestId();
        jdbcStore.recordAssociation(association(requestId));
        jdbcStore.receive(payload(requestId, "sla-event-prop-1", 1, "CONFIRMED"));
        SlaResultCallbackController callback = controller(enabledProperties(), jdbcStore);
        String bearer = "Bearer " + CALLBACK_TOKEN;

        // 同版本不同内容：409 VERSION_CONTENT_CONFLICT，SLA 侧进入人工检查。
        String conflicting = bodyOf(withContent(
                payload(requestId, "sla-event-prop-2", 1, "CONFIRMED"), "换内容"));
        ResponseEntity<SlaResultCallbackController.SlaCallbackApiResponse> response =
                callback.receive(bearer, conflicting);

        assertEquals(409, response.getStatusCode().value());
        assertEquals("VERSION_CONTENT_CONFLICT", response.getBody().message());

        // 无关联 requestId：404。
        String unknownRequest = bodyOf(payload(uniqueRequestId(), "sla-event-prop-3", 1, "CONFIRMED"));
        assertEquals(404, callback.receive(bearer, unknownRequest).getStatusCode().value());
    }

    private String validBody() {
        return bodyOf(payload("sla-event-shell-1", 1, "CONFIRMED"));
    }

    private String bodyOf(SlaResultCallbackPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
