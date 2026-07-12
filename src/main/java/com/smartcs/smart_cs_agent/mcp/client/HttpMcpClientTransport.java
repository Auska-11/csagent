package com.smartcs.smart_cs_agent.mcp.client;

import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.api.CapabilityRiskLevel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 基于 HTTP JSON-RPC 的 MCP Client Transport。
 *
 * <p>重试次数有限；副作用调用携带原 idempotencyKey，因此网络层重试不会重复写入。连续失败达到
 * 阈值后短暂打开熔断器，避免每个用户请求都阻塞等待一个已知不可用的远程服务。</p>
 */
@Component
@ConditionalOnExpression("'${smartcs.capability.provider-mode:local}' != 'local'")
public class HttpMcpClientTransport implements McpClientTransport {

    private final McpClientProperties properties;
    private final RestClient restClient;
    private final LongSupplier clock;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long circuitOpenUntil;
    private volatile String lastError = "";

    public HttpMcpClientTransport(McpClientProperties properties) {
        this(properties, buildClient(properties), System::currentTimeMillis);
    }

    HttpMcpClientTransport(McpClientProperties properties, RestClient restClient) {
        this(properties, restClient, System::currentTimeMillis);
    }

    /** 测试可注入时钟，验证熔断冷却恢复时不需要真实 sleep。 */
    HttpMcpClientTransport(McpClientProperties properties,
                           RestClient restClient,
                           LongSupplier clock) {
        this.properties = properties;
        this.restClient = restClient;
        this.clock = clock;
    }

    @Override
    public McpRemoteServerInfo initialize() {
        Map<String, Object> response = rpc("initialize", Map.of(), "init-" + UUID.randomUUID());
        Map<String, Object> result = map(response.get("result"));
        Map<String, Object> serverInfo = map(result.get("serverInfo"));
        return new McpRemoteServerInfo(
                text(result.get("protocolVersion")),
                text(serverInfo.get("name")),
                text(serverInfo.get("version"))
        );
    }

    @Override
    public Collection<CapabilityDefinition> listCapabilities() {
        Map<String, Object> response = rpc("tools/list", Map.of(), "list-" + UUID.randomUUID());
        Map<String, Object> result = map(response.get("result"));
        Object rawTools = result.get("tools");
        if (!(rawTools instanceof Collection<?> tools)) {
            throw new McpTransportException("MCP tools/list response does not contain a tools array");
        }

        List<CapabilityDefinition> definitions = new ArrayList<>();
        for (Object rawTool : tools) {
            Map<String, Object> tool = map(rawTool);
            Map<String, Object> meta = map(tool.get("_meta"));
            boolean sideEffect = bool(meta.get("sideEffect"), false);
            boolean idempotent = bool(meta.get("idempotent"), false);
            definitions.add(new CapabilityDefinition(
                    text(tool.get("name")),
                    defaultText(text(meta.get("version")), "1.0.0"),
                    text(tool.get("description")),
                    map(tool.get("inputSchema")),
                    map(tool.get("outputSchema")),
                    bool(meta.get("requiresIdentity"), false),
                    bool(meta.get("requiresAuthentication"), false),
                    sideEffect,
                    idempotent,
                    number(meta.get("defaultTimeoutMs"), 10_000),
                    risk(meta.get("riskLevel"), sideEffect)
            ));
        }
        return List.copyOf(definitions);
    }

    @Override
    public CapabilityResult<?> invoke(CapabilityCall call, ExecutionContext context) {
        // serviceToken 只证明调用服务身份，不能代表最终用户。远端没有签名委托时会拒绝身份型能力。
        if (!context.getPrincipal().isAuthenticated()) {
            throw new McpTransportException(
                    "Remote MCP invocation requires an authenticated principal"
            );
        }
        if (properties.getServiceToken() == null || properties.getServiceToken().isBlank()) {
            throw new McpTransportException("Remote MCP service token is not configured");
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("requestId", context.getTraceId());
        metadata.put("conversationId", context.getConversationId());
        metadata.put("idempotencyKey", context.getIdempotencyKey());

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", call.getCapabilityId());
        params.put("arguments", call.getArguments());
        params.put("_meta", metadata);

        Map<String, Object> response = rpc(
                "tools/call",
                params,
                defaultText(context.getTraceId(), "call-" + UUID.randomUUID())
        );
        Map<String, Object> result = map(response.get("result"));
        Map<String, Object> meta = map(result.get("_meta"));
        String providerId = defaultText(text(meta.get("providerId")), "mcp:" + properties.getServerId());
        String message = contentText(result.get("content"));
        Object data = result.get("structuredContent");
        boolean isError = bool(result.get("isError"), false);

        CapabilityResult<?> mapped;
        if (!isError) {
            mapped = CapabilityResult.success(call.getCapabilityId(), providerId, message, data);
        } else {
            String errorCode = defaultText(text(meta.get("errorCode")), "MCP_REMOTE_ERROR");
            CapabilityOutcome outcome = outcome(meta.get("outcome"));
            if (outcome == CapabilityOutcome.BUSINESS_REJECTED) {
                mapped = CapabilityResult.businessRejected(
                        call.getCapabilityId(), providerId, errorCode, message, data
                );
            } else {
                mapped = CapabilityResult.technicalFailure(
                        call.getCapabilityId(), providerId, errorCode, message
                );
            }
        }
        return bool(meta.get("replayed"), false) ? mapped.asReplayed() : mapped;
    }

    @Override
    public McpTransportHealth health() {
        return new McpTransportHealth(
                clock.getAsLong() >= circuitOpenUntil,
                consecutiveFailures.get(),
                circuitOpenUntil,
                lastError
        );
    }

    private Map<String, Object> rpc(String method, Map<String, Object> params, Object id) {
        if (properties.getServiceToken() == null || properties.getServiceToken().isBlank()) {
            throw new McpTransportException("Remote MCP Bearer JWT is not configured");
        }
        return executeWithRetry(() -> {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("jsonrpc", "2.0");
            request.put("id", id);
            request.put("method", method);
            request.put("params", params);

            Map<?, ?> response = restClient.post()
                    .uri(properties.getBaseUrl())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getServiceToken())
                    .body(request)
                    .retrieve()
                    .body(Map.class);
            Map<String, Object> mapped = map(response);
            validateJsonRpcEnvelope(mapped);
            if (mapped.containsKey("error") && mapped.get("error") != null) {
                throw new McpTransportException("Remote MCP returned JSON-RPC error");
            }
            validateResultEnvelope(method, mapped);
            return mapped;
        });
    }

    private <T> T executeWithRetry(Supplier<T> operation) {
        if (clock.getAsLong() < circuitOpenUntil) {
            throw new McpTransportException("MCP circuit is open");
        }

        RuntimeException last = null;
        int attempts = Math.max(1, properties.getMaxRetries() + 1);
        int attempted = 0;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            attempted = attempt;
            try {
                T result = operation.get();
                consecutiveFailures.set(0);
                circuitOpenUntil = 0;
                lastError = "";
                return result;
            } catch (RestClientException | McpTransportException e) {
                last = e;
                lastError = e.getClass().getSimpleName();
                if (!isRetryable(e)) {
                    break;
                }
            }
        }

        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= Math.max(1, properties.getCircuitFailureThreshold())) {
            circuitOpenUntil = clock.getAsLong() + Math.max(1, properties.getCircuitOpenMs());
        }
        throw new McpTransportException("Remote MCP request failed after " + attempted + " attempt(s)", last);
    }

    private boolean isRetryable(RuntimeException failure) {
        // JSON-RPC error和协议结构错误通常是确定性的，立即重试只会重复同一个错误。
        if (failure instanceof McpTransportException) {
            return false;
        }
        if (failure instanceof HttpClientErrorException clientError) {
            HttpStatus status = HttpStatus.resolve(clientError.getStatusCode().value());
            return status == HttpStatus.REQUEST_TIMEOUT || status == HttpStatus.TOO_MANY_REQUESTS;
        }
        // 网络连接/读取错误和 5xx 都由 RestClientException 表示，可以在有限次数内重试。
        return failure instanceof RestClientException;
    }

    private void validateJsonRpcEnvelope(Map<String, Object> response) {
        if (!"2.0".equals(text(response.get("jsonrpc"))) || !response.containsKey("id")) {
            throw new McpTransportException("Remote MCP returned an invalid JSON-RPC envelope");
        }
        boolean hasResult = response.containsKey("result") && response.get("result") != null;
        boolean hasError = response.containsKey("error") && response.get("error") != null;
        if (hasResult == hasError) {
            throw new McpTransportException("Remote MCP response must contain exactly one of result or error");
        }
    }

    /**
     * HTTP 200 只表示传输成功，不代表 JSON-RPC 结果可用。
     *
     * <p>如果缺少 result、tools 或 isError，继续映射会把空对象伪装成 Capability SUCCESS。
     * 校验放在重试闭包内，这类协议故障也会进入失败计数和熔断器。</p>
     */
    private void validateResultEnvelope(String method, Map<String, Object> response) {
        if (!response.containsKey("result") || !(response.get("result") instanceof Map<?, ?>)) {
            throw new McpTransportException("Remote MCP response does not contain a result object");
        }
        Map<String, Object> result = map(response.get("result"));
        switch (method) {
            case "initialize" -> {
                if (text(result.get("protocolVersion")).isBlank()
                        || !(result.get("serverInfo") instanceof Map<?, ?>)) {
                    throw new McpTransportException("Remote MCP initialize result is incomplete");
                }
            }
            case "tools/list" -> {
                if (!(result.get("tools") instanceof Collection<?>)) {
                    throw new McpTransportException("Remote MCP tools/list result is incomplete");
                }
            }
            case "tools/call" -> {
                Object isError = result.get("isError");
                if (!(result.get("content") instanceof Collection<?>)
                        || (isError != null && !(isError instanceof Boolean))) {
                    throw new McpTransportException("Remote MCP tools/call result is incomplete");
                }
            }
            default -> {
                // 当前 Transport 只调用上面三个方法；保留 default 让未来协议方法仍先通过通用 result 校验。
            }
        }
    }

    private static RestClient buildClient(McpClientProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(Math.max(1, properties.getTimeoutMs()));
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private String contentText(Object value) {
        if (value instanceof List<?> content && !content.isEmpty()) {
            return text(map(content.get(0)).get("text"));
        }
        return "";
    }

    private boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean bool ? bool : fallback;
    }

    private long number(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private CapabilityRiskLevel risk(Object value, boolean sideEffect) {
        try {
            return CapabilityRiskLevel.valueOf(text(value));
        } catch (IllegalArgumentException e) {
            return sideEffect ? CapabilityRiskLevel.HIGH : CapabilityRiskLevel.READ_ONLY;
        }
    }

    private CapabilityOutcome outcome(Object value) {
        try {
            return CapabilityOutcome.valueOf(text(value));
        } catch (IllegalArgumentException e) {
            return CapabilityOutcome.TECHNICAL_FAILURE;
        }
    }

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
