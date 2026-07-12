package com.smartcs.smart_cs_agent.capability.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.idempotency.core.CapabilityResultCodec;
import com.smartcs.smart_cs_agent.capability.idempotency.core.UnsupportedCapabilityResultSchemaException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 幂等结果 JSON 的往返与未来版本失败关闭测试。 */
class CapabilityResultCodecTest {

    @Test
    void shouldRoundTripBusinessRejectionAsJsonContract() {
        CapabilityResultCodec codec = new CapabilityResultCodec(new ObjectMapper());
        CapabilityResult<?> source = CapabilityResult.businessRejected(
                "return.apply",
                "local-return-domain",
                "DUPLICATE_RETURN_APPLICATION",
                "already submitted",
                Map.of("return_id", "RET-100", "submitted", false)
        );

        CapabilityResultCodec.EncodedCapabilityResult encoded = codec.encode(source);
        CapabilityResult<?> restored = codec.decode(encoded.json(), encoded.schemaVersion());

        assertEquals(CapabilityOutcome.BUSINESS_REJECTED, restored.getOutcome());
        assertEquals("DUPLICATE_RETURN_APPLICATION", restored.getErrorCode());
        Map<?, ?> data = assertInstanceOf(Map.class, restored.getData());
        assertEquals("RET-100", data.get("return_id"));
    }

    @Test
    void shouldFailClosedForUnknownFutureSchema() {
        CapabilityResultCodec codec = new CapabilityResultCodec(new ObjectMapper());

        assertThrows(
                UnsupportedCapabilityResultSchemaException.class,
                () -> codec.decode("{}", 2)
        );
    }
}
