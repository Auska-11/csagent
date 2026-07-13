package com.smartcs.smart_cs_agent.capability.core;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Capability Provider 的有界执行器。
 *
 * <p>为什么不能在超时功能里直接不断 new Thread：如果下游永久卡住，调用越多，线程就越多，
 * 最终会先把 Agent 自己拖垮。本执行器同时限制工作线程和等待队列；队列满时 Gateway 会快速返回
 * 结构化失败，让上层降级，而不是继续堆积请求。</p>
 *
 * <p>线程设为 daemon 是为了让单元测试和命令行进程可以正常退出；Spring 管理的实例仍会在关闭时
 * 主动调用 shutdownNow。Provider 应尽量响应中断，并继续在 HTTP/JDBC 层配置真实 I/O 超时。</p>
 */
@Component
public class CapabilityInvocationExecutor {

    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final ThreadPoolExecutor executor;

    public CapabilityInvocationExecutor(
            @Value("${smartcs.capability.execution.max-threads:16}") int maxThreads,
            @Value("${smartcs.capability.execution.queue-capacity:64}") int queueCapacity) {
        if (maxThreads <= 0) {
            throw new IllegalArgumentException("Capability maxThreads must be positive");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("Capability queueCapacity must be positive");
        }
        this.executor = new ThreadPoolExecutor(
                maxThreads,
                maxThreads,
                60,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "capability-provider-" + THREAD_SEQUENCE.incrementAndGet()
                    );
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        // 空闲时允许回收核心线程，避免本地学习模式长期保留 16 个空线程。
        this.executor.allowCoreThreadTimeOut(true);
    }

    public <T> Future<T> submit(Callable<T> operation) {
        return executor.submit(operation);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /** 手动 new Gateway 的测试共用一个进程级执行器，不为每个测试对象重复创建线程池。 */
    static CapabilityInvocationExecutor sharedDefault() {
        return SharedDefaultHolder.INSTANCE;
    }

    private static final class SharedDefaultHolder {
        private static final CapabilityInvocationExecutor INSTANCE =
                new CapabilityInvocationExecutor(16, 64);
    }
}
