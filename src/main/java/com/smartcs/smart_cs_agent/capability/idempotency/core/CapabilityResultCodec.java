package com.smartcs.smart_cs_agent.capability.idempotency.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 把可回放的 CapabilityResult 转成稳定 JSON。
 *
 * <p>这里故意不保存 Java 类名。持久化数据只遵守 Capability 的 JSON 输出契约，重启后 data
 * 通常会恢复成 Map/List/字符串等 JSON 结构。Workflow 若需要领域 DTO，应在自己的契约边界做
 * 显式转换，不能依赖 Jackson Default Typing。</p>
 */
@Component
public class CapabilityResultCodec {

    private static final int CURRENT_SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;

    public CapabilityResultCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public EncodedCapabilityResult encode(CapabilityResult<?> result) {
        Objects.requireNonNull(result, "capability result cannot be null");
        Snapshot snapshot = new Snapshot(
                CURRENT_SCHEMA_VERSION,
                result.getOutcome(),
                result.getCapabilityId(),
                result.getProviderId(),
                result.getMessage(),
                result.getData(),
                result.getErrorCode()
        );
        try {
            return new EncodedCapabilityResult(
                    objectMapper.writeValueAsString(snapshot),
                    CURRENT_SCHEMA_VERSION
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode idempotent capability result", e);
        }
    }

    public CapabilityResult<?> decode(String json, int storedSchemaVersion) {
        if (storedSchemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new UnsupportedCapabilityResultSchemaException(
                    storedSchemaVersion,
                    CURRENT_SCHEMA_VERSION
            );
        }
        try {
            Snapshot snapshot = objectMapper.readValue(json, Snapshot.class);
            if (snapshot.schemaVersion() != storedSchemaVersion) {
                throw new IllegalStateException(
                        "Capability result schemaVersion column does not match result JSON"
                );
            }
            return switch (snapshot.outcome()) {
                case SUCCESS -> CapabilityResult.success(
                        snapshot.capabilityId(),
                        snapshot.providerId(),
                        snapshot.message(),
                        snapshot.data()
                );
                case BUSINESS_REJECTED -> CapabilityResult.businessRejected(
                        snapshot.capabilityId(),
                        snapshot.providerId(),
                        snapshot.errorCode(),
                        snapshot.message(),
                        snapshot.data()
                );
                case TECHNICAL_FAILURE -> CapabilityResult.technicalFailure(
                        snapshot.capabilityId(),
                        snapshot.providerId(),
                        snapshot.errorCode(),
                        snapshot.message()
                );
            };
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to decode idempotent capability result", e);
        }
    }

    /** JSON 文本与其结构版本一起返回，数据库列和 JSON 内部版本可以交叉校验。 */
    public record EncodedCapabilityResult(String json, int schemaVersion) {
    }

    private record Snapshot(
            int schemaVersion,
            CapabilityOutcome outcome,
            String capabilityId,
            String providerId,
            String message,
            Object data,
            String errorCode
    ) {
    }
}
