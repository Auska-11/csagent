package com.smartcs.smart_cs_agent.capability.idempotency.api;

import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;

import java.time.Instant;
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

    /**
     * 带调用方 deadline 的等待入口。
     *
     * <p>owner 仍由 operation 自己执行；deadline 主要约束“另一个相同请求正在运行时，本请求最多等多久”。
     * 默认实现保留第三方 Store 的兼容性，内存和 JDBC 官方实现会真正执行这个时间边界。</p>
     */
    default CapabilityResult<?> execute(
            String storageKey,
            String requestFingerprint,
            Supplier<CapabilityResult<?>> operation,
            Instant deadline
    ) {
        return execute(storageKey, requestFingerprint, operation);
    }
}
