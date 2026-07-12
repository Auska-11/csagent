# 客服Agent-45：Liveness 与 Readiness 健康探针说明

## 1. 本批次结论

Phase 8 第四批把原来单一的 `/api/health` 拆成了明确的两种运行语义：

```text
/actuator/health/liveness
    -> Spring/JVM 进程是否存活

/actuator/health/readiness
    -> 当前实例是否可以安全接收新流量
```

现在具备以下能力：

- liveness 不依赖数据库，数据库故障时仍返回 HTTP 200。
- memory 模式的 readiness 不访问数据库，直接返回 UP。
- jdbc 模式的 readiness 使用一条只读 SQL 检查会话表和 Capability 幂等表。
- JDBC 运行期掉线时 readiness 返回 HTTP 503。
- JDBC 连接、Socket 读取和单条 SQL 都有明确超时，不会无限占用健康检查线程。
- 健康响应不返回 JDBC URL、账号、密码、异常原文或堆栈。
- Actuator 只暴露 `health`，`env`、`configprops` 等敏感端点不开放。
- 旧 `/api/health` 继续保留，语义明确为兼容 liveness。
- 未映射 URL 现在返回标准 404，不再被全局兜底错误转换成 500。

## 2. Liveness 和 Readiness 有什么区别

### 2.1 Liveness：这个进程还活着吗

liveness 只回答：

```text
Spring Boot 是否完成启动
HTTP 请求处理线程是否还能响应
应用是否进入不可恢复的内部状态
```

数据库、Qdrant 或外部 MCP 暂时不可用时，JVM 本身仍然活着。此时不应该让容器平台不断重启应用，
因为重启通常不能修好外部数据库，反而会制造更多连接和启动压力。

### 2.2 Readiness：这个实例现在能接新请求吗

readiness 回答：

```text
当前实例是否具备处理核心请求所需的依赖
负载均衡器是否应该继续把新流量发给它
```

jdbc 模式下，会话状态和副作用幂等记录都依赖数据库。如果数据库运行期掉线，实例继续接收聊天
请求只会产生技术失败，因此 readiness 应返回 503，让网关暂时摘除这个实例。

### 2.3 预期状态表

| 场景 | liveness | readiness | 平台行为 |
| --- | --- | --- | --- |
| memory 模式正常 | 200 UP | 200 UP | 正常接流量 |
| jdbc 模式正常 | 200 UP | 200 UP | 正常接流量 |
| 数据库运行期掉线 | 200 UP | 503 DOWN | 停止新流量，不重启 JVM |
| Spring 进程不可响应 | 失败 | 失败 | 重启实例 |

## 3. 为什么旧 `/api/health` 不够

旧接口固定返回：

```json
{
  "status": "UP",
  "application": "smart-cs-agent"
}
```

它适合前端调试时确认“后端端口通了”，但即使 MySQL 已经掉线也会继续返回 UP。部署系统如果把它
当 readiness，就会继续把用户请求发给一个无法保存会话和幂等记录的实例。

本批没有删除旧接口，因为前端 Demo 和早期文档可能仍在使用它。代码注释已经明确：

```text
/api/health = 兼容 liveness
```

新部署应使用 Actuator 的两个专用路径。

## 4. 组件结构

```text
Spring Boot Actuator HealthEndpoint
                |
                +-- liveness group
                |      `-- livenessState
                |
                `-- readiness group
                       +-- readinessState
                       `-- persistence
                              |
                              v
                    PersistenceHealthIndicator
                              |
                              v
                    JdbcDatabaseClient
```

职责划分：

| 组件 | 负责什么 | 不负责什么 |
| --- | --- | --- |
| `HealthCheckController` | 保留旧 HTTP 存活检查 | 外部依赖检查 |
| Actuator liveness group | Spring 进程存活状态 | 数据库状态 |
| Actuator readiness group | 聚合是否可接流量 | 自动修复依赖 |
| `PersistenceHealthIndicator` | memory/jdbc 分支和安全只读探针 | 业务查询、建表、写数据 |
| `JdbcDatabaseClient` | 执行 JDBC 查询并限制连接、读取和 SQL 时长 | 决定健康状态 |

## 5. PersistenceHealthIndicator 如何工作

该组件始终注册，Actuator 组件 ID 为 `persistence`。

### 5.1 memory 模式

```text
JdbcDatabaseClient.isEnabled() == false
        |
        v
直接返回 UP(mode=memory)
```

不会加载驱动、打开连接或执行 SQL，因此默认单元测试和 CI 不需要 MySQL。

### 5.2 jdbc 模式

探针使用一条只读 SQL：

```sql
SELECT
    (SELECT 1 FROM cs_agent_session LIMIT 1) AS session_ready,
    (SELECT 1 FROM cs_capability_idempotency LIMIT 1) AS idempotency_ready;
```

为什么这样写：

1. 一条 SQL 只创建一个 JDBC 连接。
2. 即使两张表没有业务行，子查询也可以正常返回空值，不会误判为 DOWN。
3. 任一表不存在、不可读或数据库连接失败都会抛异常。
4. 不读取用户消息、会话 JSON 或幂等结果内容。

成功时返回 UP；捕获异常后返回 DOWN，但只保留固定安全原因：

```text
agent persistence is unavailable
```

### 5.3 为什么需要三层超时

一次数据库探针可能在三个不同阶段阻塞：

| 配置 | 默认值 | 保护的阶段 |
| --- | ---: | --- |
| `connect-timeout-ms` | 3000 ms | 建立 TCP/JDBC 连接 |
| `socket-timeout-ms` | 10000 ms | 连接建立后等待数据库网络响应 |
| `query-timeout-seconds` | 5 s | 客户端等待 SQL，超时后请求驱动尝试取消 |

`connectTimeout` 和 `socketTimeout` 通过 MySQL JDBC Driver 的 `Properties` 传入；
`queryTimeout` 在每个 `PreparedStatement` 执行前设置。这样超时规则同时覆盖 readiness、SessionStore、
Capability 幂等 Store 和业务 Repository，而不是在健康探针里写一套特殊逻辑。

三个值必须大于 0，因为 JDBC 中的 0 通常表示“永不超时”。超时发生后会沿统一异常路径让 readiness
返回 DOWN，但 liveness 仍不依赖数据库。

## 6. Actuator 配置

`application.yml` 只暴露 health：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      probes:
        enabled: true
      show-components: always
      show-details: never
      group:
        liveness:
          include: livenessState
        readiness:
          include: readinessState,persistence
```

关键安全设置：

- `include: health`：不开放 `env`、`beans`、`configprops`、`heapdump` 等端点。
- `show-components: always`：可以看到哪个组件 UP/DOWN，便于部署排查。
- `show-details: never`：不返回 Indicator 的 detail，避免内部配置泄露。

例如数据库故障时，对外只看到：

```json
{
  "status": "DOWN",
  "components": {
    "persistence": {
      "status": "DOWN"
    },
    "readinessState": {
      "status": "UP"
    }
  }
}
```

## 7. HTTP 状态码

| 路径 | 正常 | 故障 |
| --- | --- | --- |
| `/api/health` | 200 | 只要进程能响应就仍是 200 |
| `/actuator/health/liveness` | 200 | 进程内部不可用时失败 |
| `/actuator/health/readiness` | 200 | 任一 readiness 组件 DOWN 时 503 |
| `/actuator/env` | 404 | 始终不暴露 |

测试 `/actuator/env` 时还发现一个旧问题：Spring 6 会把未找到的静态资源包装为
`NoResourceFoundException`，原全局兜底处理器把它返回成 500。本批增加专门处理器，将未映射路径
稳定转换成 `404 NOT_FOUND`。

## 8. Docker 配置

`docker-compose.yml` 现在会把持久化配置传入 Agent 容器：

```text
SMARTCS_PERSISTENCE_MODE
SMARTCS_JDBC_DRIVER
SMARTCS_JDBC_URL
SMARTCS_JDBC_USERNAME
SMARTCS_JDBC_PASSWORD
SMARTCS_JDBC_CONNECT_TIMEOUT_MS
SMARTCS_JDBC_SOCKET_TIMEOUT_MS
SMARTCS_JDBC_QUERY_TIMEOUT_SECONDS
```

默认仍是 memory，不会强制要求 MySQL。容器连接宿主机 MySQL 时，macOS/Windows 通常使用：

```text
jdbc:mysql://host.docker.internal:3306/smart_cs_agent
```

若 MySQL 也是 Compose 服务，应把 host 改成对应服务名。

## 9. 测试覆盖

| 测试 | 验证内容 |
| --- | --- |
| `PersistenceHealthIndicatorTest` | memory 不访问 DB、jdbc 两表可读、异常安全 DOWN |
| `HealthEndpointIntegrationTest` | liveness/readiness UP、旧接口兼容、敏感端点 404 |
| `ReadinessFailureEndpointTest` | persistence DOWN 时 readiness 503、liveness 200、详情不泄露 |
| `GlobalExceptionHandlerTest` | 未映射资源返回 404 而不是 500 |
| `JdbcDatabaseClientTimeoutTest` | 参数传入、执行顺序、安全默认值、非法配置和 JDBC 资源关闭 |

完整验证结果：

```text
mvn test
Tests run: 306, Failures: 0, Errors: 0, Skipped: 0

mvn -q -DskipTests package
BUILD SUCCESS
```

## 10. 当前限制

1. jdbc 模式下多个 Repository 和 Store 仍在构造器中建表；数据库在启动阶段不可达时，应用会在
   超时后启动失败，readiness 只能检测成功启动后的运行期故障。
2. 当前探针只检查 `cs_agent_session` 和 `cs_capability_idempotency`，没有逐张检查所有业务表。
3. `JdbcDatabaseClient` 仍使用 DriverManager，每次探针创建一个新连接；已有明确超时，但没有
   HikariCP、连接复用或探针缓存，高频访问仍会增加数据库压力。
4. 没有真实 MySQL/Testcontainers 健康端点测试，JDBC DOWN 由可控测试替身模拟。
5. 当前 readiness 不检查模型 API、Qdrant、远程 MCP 或售后工单平台。这些依赖已有降级策略，不应
   在第一版全部变成硬 readiness 依赖。
6. 健康端点默认不要求业务 token，适合平台探针；公网部署时仍应由网关限制来源和访问频率。
7. Compose 尚未配置容器原生 `healthcheck`，不同部署平台应使用各自的 HTTP 探针配置。

## 11. 下一批建议

后续可以按顺序继续：

1. 使用 Testcontainers MySQL 验证真实 JDBC 方言、唯一键和 readiness。
2. 引入 HikariCP，补充连接池容量、等待、验证和泄漏检测治理；现有直接 JDBC 超时作为第一层保护。
3. 用 Flyway 管理表结构，移除构造器中的建表逻辑。
4. 根据部署平台配置 readiness/liveness 探针和优雅下线。
5. WorkflowStateMigrator 注册链已在下一批完成，详见
   `客服Agent-46-WorkflowStateMigrator注册链说明.md`。

本批完成后，项目已经能够明确表达“进程还活着”和“实例现在可接流量”是两件不同的事。
