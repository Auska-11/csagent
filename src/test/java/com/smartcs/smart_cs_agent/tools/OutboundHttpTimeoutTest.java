package com.smartcs.smart_cs_agent.tools;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;

/**
 * 外部 HTTP 真实读取超时测试。
 *
 * <p>MockRestServiceServer 适合验证请求结构，但不会证明底层 Socket 真的受时间限制。本测试启动 JDK
 * 本地 HTTP Server，故意晚于 read timeout 返回，从生产构造函数验证三个客户端都能及时退出。</p>
 */
class OutboundHttpTimeoutTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldBoundAfterSalesTicketReadTime() throws IOException {
        HandoffProperties properties = new HandoffProperties();
        configureTimeout(properties.getAfterSales(), startSlowServer());
        HttpAfterSalesTicketClient client = new HttpAfterSalesTicketClient(properties);

        assertTimeout(Duration.ofSeconds(2), () ->
                assertThrows(IllegalStateException.class, () -> client.createTicket(
                        new AfterSalesTicketCreateRequest(
                                "转人工",
                                "故障注入",
                                "USER-001",
                                "general",
                                "handoff",
                                "MEDIUM",
                                "timeout-test"
                        )
                ))
        );
    }

    @Test
    void shouldBoundEmbeddingReadTime() throws IOException {
        RagProperties properties = new RagProperties();
        properties.getEmbedding().setBaseUrl(startSlowServer());
        properties.getEmbedding().setConnectTimeoutMs(200);
        properties.getEmbedding().setReadTimeoutMs(50);
        OpenAiTextEmbeddingClient client = new OpenAiTextEmbeddingClient(properties);

        assertTimeout(Duration.ofSeconds(2), () ->
                assertThrows(IllegalStateException.class, () -> client.embed("超时测试"))
        );
    }

    @Test
    void shouldBoundQdrantReadTime() throws IOException {
        RagProperties properties = new RagProperties();
        properties.getQdrant().setBaseUrl(startSlowServer());
        properties.getQdrant().setConnectTimeoutMs(200);
        properties.getQdrant().setReadTimeoutMs(50);
        QdrantKnowledgeVectorStore store = new QdrantKnowledgeVectorStore(properties);

        assertTimeout(Duration.ofSeconds(2), () ->
                assertThrows(IllegalStateException.class, () -> store.ensureCollection(3))
        );
    }

    private void configureTimeout(HandoffProperties.AfterSales afterSales, String baseUrl) {
        afterSales.setBaseUrl(baseUrl);
        afterSales.setConnectTimeoutMs(200);
        afterSales.setReadTimeoutMs(50);
    }

    private String startSlowServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(300);
                byte[] body = "{\"success\":true,\"data\":{}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException clientAlreadyTimedOut) {
                // 客户端按预期先超时并关闭连接时，服务端写响应可能得到 Broken pipe。
            } finally {
                exchange.close();
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
