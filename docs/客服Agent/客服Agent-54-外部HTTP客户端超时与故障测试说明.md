# 客服 Agent 外部 HTTP 客户端超时与故障测试说明

## 1. 本批解决什么问题

项目已经为 MCP 和 JDBC 配置超时，但以下三个外部 HTTP Client 仍使用 RestClient 默认行为：

- `HttpAfterSalesTicketClient`。
- `OpenAiTextEmbeddingClient`。
- `QdrantKnowledgeVectorStore`。

它们都位于 Agent 会话执行链中。如果远端建立连接后一直不返回，当前请求会持续占用：

- Web 请求线程。
- 同一会话的 `ReentrantLock`。
- AgentRuntime 当前 Turn。
- RAG 首次建索引时的同步锁。

本批为三个依赖分别增加连接和读取超时，并使用真实 JDK 慢 HTTP Server 验证读取时间确实有上限。

本批完成后默认测试套件共 `375` 项，0 失败、0 错误、0 跳过。

## 2. 两种超时分别限制什么

### 连接超时

连接超时限制：

```text
客户端开始建立 TCP 连接
  -> 连接成功
  或
  -> 超过 connectTimeoutMs 后失败
```

它主要处理：

- 目标地址不可达。
- 网络路由黑洞。
- 远端没有接受连接。

### 读取超时

读取超时限制：

```text
TCP 连接已经建立
  -> 等待 HTTP 响应数据
  -> 正常返回
  或
  -> 超过 readTimeoutMs 后失败
```

它主要处理：

- 远端线程卡死。
- 远端处理非常慢。
- 代理建立连接后不返回数据。

只配置连接超时不能解决“连接成功但永远没响应”，所以两个值都必须配置。

## 3. 统一 BoundedRestClientFactory

三个客户端都使用 Spring `RestClient`。如果分别复制 requestFactory 代码，后续容易出现：

- 某个客户端漏配 read timeout。
- 默认值不一致。
- 一个客户端允许 0，另一个拒绝 0。
- 修改实现时只更新其中两份。

本批新增：

```text
BoundedRestClientFactory
  -> SimpleClientHttpRequestFactory
       -> connectTimeout
       -> readTimeout
  -> RestClient
```

它只负责创建有时间边界的客户端，不负责：

- 自动重试。
- 熔断。
- 业务降级。
- 强制中止已经提交到远端的副作用。

这些策略需要根据调用的副作用性质分别设计。

## 4. 为什么配置不能小于等于 0

部分 HTTP/JDBC 实现中，0 并不是“立即超时”，而是“永不超时”。

因此所有 setter 和工厂都会检查：

```text
timeout > 0
```

非法配置会在 Spring 绑定或创建 Client 时失败，而不是带着无限等待风险继续启动。

## 5. 配置项

### 售后工单平台

| 环境变量 | 默认值 |
| --- | ---: |
| SMARTCS_AFTER_SALES_CONNECT_TIMEOUT_MS | 3000 ms |
| SMARTCS_AFTER_SALES_READ_TIMEOUT_MS | 10000 ms |

对应配置：

```yaml
smartcs:
  handoff:
    after-sales:
      connect-timeout-ms: 3000
      read-timeout-ms: 10000
```

### Embedding 服务

| 环境变量 | 默认值 |
| --- | ---: |
| SMARTCS_EMBEDDING_CONNECT_TIMEOUT_MS | 3000 ms |
| SMARTCS_EMBEDDING_READ_TIMEOUT_MS | 10000 ms |

### Qdrant

| 环境变量 | 默认值 |
| --- | ---: |
| SMARTCS_QDRANT_CONNECT_TIMEOUT_MS | 3000 ms |
| SMARTCS_QDRANT_READ_TIMEOUT_MS | 10000 ms |

`application.yml`、`docker-compose.yml` 和 `.env.example` 已同步这些变量。

## 6. 为什么每个依赖保留独立配置

三个依赖的延迟特征不同：

- 售后工单创建通常是小型 JSON 写请求。
- Embedding 可能受模型排队和文本长度影响。
- Qdrant 首次建表、批量 upsert 和查询的耗时不同。

如果只使用一个全局 HTTP_TIMEOUT：

- 为了容忍 Embedding 慢请求，工单创建也可能等待过久。
- 为了让工单快速失败，Embedding 又可能频繁误超时。

独立配置可以按真实 SLO 调整，同时仍通过统一工厂保证“每个客户端都有上限”。

## 7. 生产构造函数和测试构造函数为什么不同

生产构造函数：

```text
Properties
  -> BoundedRestClientFactory
  -> 带真实 connect/read timeout 的 RestClient
```

已有请求结构单元测试使用 `MockRestServiceServer.bindTo(builder)`。它会把 Mock requestFactory 安装到
传入的 Builder 上。如果测试构造函数再次覆盖 requestFactory，Mock Server 会失效。

因此保留包内测试构造函数：

```text
Properties + test RestClient.Builder
  -> 保留 Mock requestFactory
```

生产超时不依赖这个 Mock 构造函数，而是由 `OutboundHttpTimeoutTest` 通过公共生产构造函数验证。

## 8. 真实慢 HTTP Server 怎样验证

`MockRestServiceServer` 能验证 URL、Header 和 JSON，但它不能证明 Socket 读取真的会超时。

`OutboundHttpTimeoutTest` 使用 JDK `HttpServer`：

```text
测试客户端 readTimeout = 50 ms
本地服务接收请求
  -> 故意等待 300 ms
  -> 再尝试返回 HTTP 200
客户端应先在超时边界内失败
```

三个测试分别真实调用：

- 售后工单 `POST /api/tickets`。
- Embedding `POST /embeddings`。
- Qdrant `PUT /collections/{collection}`。

每个调用外层还有 2 秒的 JUnit `assertTimeout`，防止底层超时配置完全失效时测试本身长时间挂住。

## 9. 三条失败链路怎样降级

### 售后工单

```text
HTTP 超时
  -> HttpAfterSalesTicketClient 抛出安全异常
  -> HandoffTools 捕获
  -> 生成 REMOTE_HANDOFF_PENDING 内存工单
```

这让用户请求能及时返回，但 pending 工单尚未持久化，仍是下一批需要解决的可靠性缺口。

### Embedding 或 Qdrant

```text
HTTP 超时
  -> Client / VectorStore 抛出异常
  -> KnowledgeTools.searchByEmbedding 捕获
  -> 回退本地 Markdown RAG
```

因此 embedding-qdrant 模式的高级依赖失败时，政策问答仍可使用本地关键词和 n-gram 召回。

## 10. 超时不等于操作没有发生

这是本批最重要的边界。

售后 Client 读取超时时，可能出现：

```text
工单平台已经创建工单
  -> 返回响应时网络中断
  -> Agent 只看到 read timeout
```

因此不能简单理解成“超时 = 远端没有执行”。安全重试需要：

- 复用同一个 Idempotency-Key。
- 远端平台真正支持幂等。
- 能按 key 查询已有结果。
- 对结果未知状态进行持久化和后台核对。

本项目已经把 Idempotency-Key 传给售后平台，但 REMOTE_HANDOFF_PENDING 仍只保存在内存中，暂时没有可靠
补建和查询流程。

Embedding 和 Qdrant 主要是读或按稳定文档 ID upsert，副作用风险比工单创建低，但重试、退避和熔断仍应
在未来按剩余 deadline 统一设计。

## 11. 测试覆盖

### OutboundHttpPropertiesTest

- 三个依赖默认连接超时均为 3000 ms。
- 三个依赖默认读取超时均为 10000 ms。
- 0 和负数配置全部失败。

### OutboundHttpTimeoutTest

- 售后工单真实慢 HTTP 读取超时。
- Embedding 真实慢 HTTP 读取超时。
- Qdrant 真实慢 HTTP 读取超时。
- 每条故障测试自身不超过 2 秒。

### 既有回归

- `HttpAfterSalesTicketClientTest` 继续验证 Header、Idempotency-Key 和请求 JSON。
- `KnowledgeToolsTest` 继续验证向量依赖失败后回退本地检索。
- 全量 Spring Context 验证新增配置能正常绑定和装配。

最终结果：

```text
Tests run: 375
Failures: 0
Errors: 0
Skipped: 0
```

## 12. 当前边界

1. 三个客户端还没有统一重试、退避、抖动、熔断和 bulkhead。
2. 超时配置是每次 HTTP 调用上限，还不是贯穿整个 Agent Turn 的绝对 deadline。
3. LLM ChatModel 仍有独立 60 秒超时和默认重试问题，本批没有修改 LangChain4j 内部策略。
4. 售后超时后的 pending 工单没有持久化、后台补建和结果查询，仍可能形成假成功语义。
5. Qdrant 首次索引在 synchronized 区域内执行；有超时但仍可能阻塞其他首次检索请求直到当前调用结束。
6. 当前真实故障测试覆盖 read timeout，没有依赖不稳定网络环境模拟 connect timeout。
7. 客户端异常 message 仍属于内部错误对象；不能在未来直接写入公开 Trace 或用户回复。

## 13. 下一步建议

优先顺序：

1. 新增 `PendingHandoffStore` 或 Outbox，把结果未知和待补建状态可靠落库。
2. 建立售后工单查询/补建任务，始终复用原 Idempotency-Key。
3. 为整个 Turn 传播绝对 deadline，并使用剩余预算限制每次外部调用。
4. 再评估有限重试、退避、熔断和 bulkhead。
5. 增加连接拒绝、连接重置、429、5xx 和线程池饱和故障测试。

## 14. 初学者阅读顺序

1. `HandoffProperties` 和 `RagProperties`：看每个依赖怎样保存独立超时。
2. `BoundedRestClientFactory`：看超时怎样真正进入底层 requestFactory。
3. `HttpAfterSalesTicketClient`：看生产构造和测试构造为什么不同。
4. `OpenAiTextEmbeddingClient` 与 `QdrantKnowledgeVectorStore`：看同一个工厂怎样复用。
5. `OutboundHttpTimeoutTest`：最后看真实慢服务器怎样证明配置有效。

核心原则：

> 外部依赖失败不可怕，不受时间限制的失败才会把一个局部故障扩散成线程和会话资源耗尽。
