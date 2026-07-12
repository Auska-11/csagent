package com.smartcs.smart_cs_agent.capability.idempotency.api;

import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;

import java.util.function.Supplier;

/**
 * 副作用 Capability 的幂等执行 SPI。
 *
 * <p>Gateway 只依赖这个接口，不关心幂等记录保存在当前进程、MySQL 还是未来的 Redis 中。
 * 实现类必须保证：同一个 storageKey 和同一份请求指纹只执行一次 operation，后续调用回放
 * 已保存结果；同一个 key 如果对应不同指纹，则必须拒绝，不能错误回放另一笔操作。</p>
 */
public interface CapabilityIdempotencyStore {

    CapabilityResult<?> execute(
            String storageKey,
            String requestFingerprint,
            Supplier<CapabilityResult<?>> operation
    );
}
