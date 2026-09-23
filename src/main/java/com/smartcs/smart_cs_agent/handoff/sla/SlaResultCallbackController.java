package com.smartcs.smart_cs_agent.handoff.sla;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * SLA 结果回调接收端：POST /api/integrations/v1/sla-results。
 *
 * <p>验证流程：独立回调令牌（恒定时间比对）→ 载荷格式 → 租户绑定（403）→ Store 单事务
 * 去重/版本/撤回处理 → 按 SLA 确认协议返回固定外壳。端点未启用时按 404 处理，让 SLA 侧
 * 进入可见的重试/人工状态。</p>
 *
 * <p>边界（契约第一阶段）：这里只持久化结果，绝不自动发送消息、绝不恢复机器人、
 * 绝不更新商品页面；消费结果的动作留给人工或后续显式授权。</p>
 */
@RestController
@RequestMapping("/api/integrations/v1")
public class SlaResultCallbackController {

    private static final Logger log = LoggerFactory.getLogger(SlaResultCallbackController.class);

    private static final String BEARER_PREFIX = "Bearer ";

    private final SlaV1Properties properties;
    private final SlaIntegrationStore slaIntegrationStore;
    private final ObjectMapper objectMapper;

    public SlaResultCallbackController(SlaV1Properties properties,
                                       SlaIntegrationStore slaIntegrationStore,
                                       ObjectMapper objectMapper) {
        this.properties = Objects.requireNonNull(properties, "properties cannot be null");
        this.slaIntegrationStore = Objects.requireNonNull(
                slaIntegrationStore,
                "slaIntegrationStore cannot be null"
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper cannot be null");
    }

    @PostMapping(value = "/sla-results", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SlaCallbackApiResponse> receive(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody String rawBody) {

        if (!properties.isCallbackEnabled()) {
            // 未配置回调令牌/租户：按“端点不存在”处理，SLA 侧重试后进入人工检查，不静默吞结果。
            return reject(404, "NOT_FOUND");
        }
        String presented = extractBearerToken(authorization);
        if (presented == null || !tokenMatches(presented)) {
            return reject(401, "UNAUTHORIZED");
        }

        SlaResultCallbackPayload payload;
        try {
            payload = objectMapper.readValue(rawBody, SlaResultCallbackPayload.class);
        } catch (JsonProcessingException malformed) {
            log.warn("SLA result callback body is not valid JSON");
            return reject(400, "INVALID_REQUEST");
        }
        if (payload == null) {
            return reject(400, "INVALID_REQUEST");
        }
        String formatProblem = payload.validateFormat();
        if (formatProblem != null) {
            log.warn("SLA result callback payload rejected: eventId={} problem={}",
                    payload.eventId(), formatProblem);
            return reject(400, "INVALID_REQUEST");
        }
        if (!properties.getCallbackTenantId().equals(payload.tenantId())) {
            // 令牌绑定租户与载荷租户不一致：按身份冒用处理。
            log.warn("SLA result callback tenant mismatch: eventId={}", payload.eventId());
            return reject(403, "FORBIDDEN");
        }

        if (slaIntegrationStore instanceof InMemorySlaIntegrationStore) {
            // 持久化护栏：鉴权与格式都通过后才检查存储形态。内存 Store 重启即丢结果，
            // 若仍向 SLA 返回成功确认，等于承认一个无法兑现的持久化承诺（契约要求
            // “仅写内存或打日志不能作为回调成功确认”）。返回 500 让 SLA 侧重试并保持可见。
            log.error("SLA result callback received while persistence store is memory-only; "
                    + "refusing to acknowledge");
            return reject(500, "PERSISTENCE_REQUIRED");
        }

        SlaResultDisposition disposition;
        try {
            disposition = slaIntegrationStore.receive(payload);
        } catch (SlaResultCallbackRejectedException rejected) {
            log.warn("SLA result callback rejected: eventId={} status={} code={}",
                    payload.eventId(), rejected.getStatus(), rejected.getCode());
            return reject(rejected.getStatus(), rejected.getCode());
        } catch (RuntimeException storeFailure) {
            log.error("SLA result callback store failure: eventId={} exceptionType={}",
                    payload.eventId(), storeFailure.getClass().getSimpleName());
            return reject(500, "INTERNAL_ERROR");
        }

        log.info("SLA result callback accepted: eventId={} requestId={} disposition={}",
                payload.eventId(), payload.requestId(), disposition);
        return ResponseEntity.ok(SlaCallbackApiResponse.success(
                new SlaCallbackAck(payload.eventId(), disposition.name())
        ));
    }

    private static String extractBearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = authorization.substring(BEARER_PREFIX.length());
        return token.isBlank() ? null : token;
    }

    private boolean tokenMatches(String presented) {
        // 恒定时间比对，避免按响应时间逐字节猜令牌。
        return MessageDigest.isEqual(
                properties.getCallbackToken().getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static ResponseEntity<SlaCallbackApiResponse> reject(int status, String code) {
        return ResponseEntity.status(status).body(SlaCallbackApiResponse.failure(code));
    }

    /** SLA 确认协议的固定响应外壳。 */
    public record SlaCallbackApiResponse(boolean success, Object data, String message) {

        static SlaCallbackApiResponse success(Object data) {
            return new SlaCallbackApiResponse(true, data, null);
        }

        static SlaCallbackApiResponse failure(String code) {
            return new SlaCallbackApiResponse(false, null, code);
        }
    }

    public record SlaCallbackAck(String eventId, String disposition) {
    }
}
