package com.smartcs.smart_cs_agent.tools;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 外部 HTTP 依赖共用的有界 RestClient 工厂。
 *
 * <p>职责：统一设置连接超时和读取超时，防止网络黑洞长期占用 Agent 会话锁和 Web 线程。</p>
 * <p>不负责：不决定重试、熔断或业务降级；这些策略仍由具体调用方按副作用风险处理。</p>
 *
 * <p>调用链：售后/Embedding/Qdrant Client -> 本工厂 -> Spring RestClient。</p>
 */
final class BoundedRestClientFactory {

    private BoundedRestClientFactory() {
    }

    /**
     * 创建同时受连接和读取时间限制的客户端。
     *
     * <p>超时必须大于 0。SimpleClientHttpRequestFactory 中的 0 通常表示永不超时，
     * 这会重新引入本批要关闭的无限等待风险。</p>
     */
    static RestClient create(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
        requirePositive("connectTimeoutMs", connectTimeoutMs);
        requirePositive("readTimeoutMs", readTimeoutMs);

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    static int requirePositive(String propertyName, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(propertyName + " must be greater than 0");
        }
        return value;
    }
}
