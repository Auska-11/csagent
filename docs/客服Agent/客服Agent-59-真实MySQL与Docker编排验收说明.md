# 客服 Agent 真实 MySQL 与 Docker 编排验收说明

## 1. 本批解决什么问题

此前 JDBC 测试主要使用 SQL 语义替身，Dockerfile 也只复制 `src`，导致本地 Maven JAR 含有 Skill Package，但容器构建阶段可能缺少根目录 `skills/`。

本批完成两部分工作：

1. 使用真实 MySQL 8.4.0 容器验证 Session、Capability 幂等和 Outbox 数据库语义。
2. 修复 Dockerfile/Compose 的 Skill、MySQL、依赖顺序和健康检查配置。

真实 MySQL IT、Agent 镜像构建、完整 Compose 启动、数据库掉线恢复和 Agent 重启持久化现已全部实际运行通过。
本批不是只检查配置文件，而是完成了从镜像到故障恢复的端到端部署验收。

## 2. Dockerfile 修复

旧构建只执行：

```dockerfile
COPY src src
```

但 Maven 的 `pom.xml` 会从项目根目录 `skills/` 复制 9 个 Skill Package。Docker build context 中如果没有复制该目录，容器 JAR 就会缺少运行时 Skill。

现在增加：

```dockerfile
COPY skills skills
```

运行镜像还安装最小 `curl`，并用 `/actuator/health/readiness` 作为 Docker `HEALTHCHECK`。

## 3. Compose MySQL 编排

Compose 新增固定版本 `mysql:8.4.0`：

- 数据库名：`smart_cs_agent`。
- 普通应用用户：`smartcs`。
- `mysql_data` 持久卷。
- `mysqladmin ping` 健康检查。
- Agent 等待 MySQL `service_healthy` 后再启动。
- Compose 中 Agent 默认使用 JDBC，单独运行 JAR 时仍默认 memory。

宿主机默认端口使用 3307，而不是 3306。真实启动时发现本机 3306 已被占用，因此使用 3307 能避免与开发者已有 MySQL 冲突；容器内部仍是标准 3306。

## 4. 应用健康检查

Dockerfile 和 Compose 都检查：

```text
GET /actuator/health/readiness
```

这不是简单检查 JVM 进程：JDBC 模式下 readiness 会读取 Agent 核心表。数据库不可用时应用不应继续接收新流量，但 liveness 仍保持独立，避免容器因外部数据库故障反复重启。

## 5. 真实 MySQL IT 怎么运行

启动数据库：

```bash
docker compose up -d mysql
docker compose ps mysql
```

显式运行真实验收：

```bash
mvn -Dtest=RealMySqlPersistenceIT test
```

`RealMySqlPersistenceIT` 以 `IT` 结尾，所以默认 `mvn test` 不执行它，不会让没有 Docker 的普通开发和 CI 失败。需要验收真实数据库时必须显式运行，不能用默认测试结果冒充真实 MySQL 证据。

## 6. 实际验收结果

本次实际环境：

```text
Docker Server: 24.0.7
MySQL image: mysql:8.4.0
Host port: 3307
Agent image: smart-cs-agent:latest
Agent image digest: ee63903b76c1...
MySQL status: healthy
Agent status: healthy
Qdrant status: running
```

真实测试结果：

```text
Tests run: 1
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

## 7. 真实数据库覆盖内容

### Session CAS

- Store A 创建会话 version 1。
- Store B 重建并读取相同记录。
- Store B 更新到 version 2。
- Store A 使用旧 version 保存时收到 `SessionVersionConflictException`。
- 使用正确 version 删除成功。

### Capability 幂等并发

- 两个 `JdbcCapabilityIdempotencyStore` 实例并发使用同一个 key。
- 第一个 Provider 被阻塞时，第二个实例等待数据库结果。
- 两个调用最终都成功。
- Provider 总调用次数严格为 1。

### Outbox 租约

- Store A 领取记录。
- 未过期时 Store B 无法领取。
- 租约到期后 Store B 使用 CAS 接管。
- 接管者推进到 `NEEDS_MANUAL_REVIEW`。

### SQL 查询超时

- 执行真实 MySQL `SELECT SLEEP(2)`。
- JDBC query timeout 配置为 1 秒。
- 调用在 3 秒外层边界内抛出安全数据库异常。

## 8. 静态 Docker 回归门禁

`DockerDeploymentConfigurationTest` 默认随 `mvn test` 运行，检查：

- Dockerfile 复制 `skills/`。
- Dockerfile 有 readiness HEALTHCHECK。
- Compose 包含固定 MySQL 镜像。
- Agent 等待 MySQL healthy。
- Compose 默认使用 `jdbc:mysql://mysql:3306/...`。
- MySQL 数据卷和应用 readiness 存在。
- `.dockerignore` 只使用 ASCII 规则，兼容 Docker 24 的 BuildKit。

新增兼容性门禁后，默认测试共 397 项，0 失败、0 错误、0 跳过。

## 9. 完整 Docker 运行证据

### 9.1 构建时发现并修复 Docker 24 兼容问题

第一次真实构建没有停在 Java 编译，而是在发送构建上下文时失败：

```text
header key "exclude-patterns" contains value with non-printable ASCII characters
```

根因是旧 `.dockerignore` 直接包含中文路径 `docs/工单平台`。Docker Desktop 24.0.7 的 BuildKit 会把
ignore 规则放入内部 `exclude-patterns` 请求头，旧版本不能正确处理其中的 UTF-8 字符。该目录已经不存在，
而 Dockerfile 本来也不会复制文档，因此规则改为纯 ASCII 的 `docs`。新的静态测试会确保以后不会再次加入
非 ASCII ignore 规则。

修复后构建成功，镜像摘要为：

```text
sha256:ee63903b76c100bf0347402d786b491a6d56f44af7cd4288e5f5755ab5fd1f0b
```

### 9.2 Skill 与 readiness

完整 Compose 启动后：

- MySQL 为 `healthy`。
- Agent 为 `healthy`。
- Qdrant 正常运行。
- readiness 返回 HTTP 200，组件包含 `persistence=UP`。
- 运行容器的 JAR 内实际存在 9 个 `BOOT-INF/classes/skills/*/manifest.yaml`。
- Agent 启动日志实际打印了 9 条 `Loaded Agent Skill package`。

运行镜像使用精简 JRE，没有开发期 `jar` 命令。验收时使用 `docker cp` 将运行中的 JAR 复制到宿主机临时
目录，再用宿主机 JDK 执行 `jar tf`；不为了验收给生产镜像安装整套 JDK。

### 9.3 数据库掉线与恢复

停止 MySQL、保持 Agent 运行后：

1. readiness 立即返回 HTTP 503。
2. JSON 明确显示 `persistence=DOWN`，不是只根据进程猜测。
3. Docker healthcheck 在连续 6 次失败后把 Agent 标记为 `unhealthy`。

重新启动 MySQL 后：

1. readiness 自动恢复 HTTP 200，`persistence=UP`。
2. Agent 自动恢复 `healthy`。
3. `FailingStreak` 回到 0，不需要重启 Agent。

### 9.4 Agent 重启与持久化

先通过真实 JWT 聊天接口创建会话 `docker-acceptance-session`，接口返回 HTTP 200。重启前后比对结果：

```text
Session schema/version: 1 / 1
Session state SHA-256: bf72727737f016cc184a07fbb9bfb0f027abe5a54e1ad22d055e634b12c187a9
Outbox status/version: NEEDS_MANUAL_REVIEW / 4
```

重启前后上述值完全一致；重启后的 Memory API 还能正常反序列化会话，并返回用户消息、客服降级回复和
已完成的 `customer_service` Workflow。这比只比较数据库行数更强，因为它同时证明数据仍在且新进程可读。

验收结束后已执行 `docker compose down`。Agent、MySQL 和 Qdrant 容器均已停止并移除；没有使用 `-v`，
`csagent_mysql_data` 数据卷仍然保留。

## 10. 完整容器复验命令

构建并启动：

```bash
docker compose build smart-cs-agent
docker compose up -d
docker compose ps
curl --fail http://localhost:8080/actuator/health/readiness
```

检查运行容器的 Skill 清单：

```bash
docker cp smart-cs-agent:/app/app.jar /tmp/smart-cs-agent-container.jar
jar tf /tmp/smart-cs-agent-container.jar \
  | rg '^BOOT-INF/classes/skills/.*/manifest.yaml$'
```

数据库掉线与恢复：

```bash
docker compose stop mysql
curl http://localhost:8080/actuator/health/readiness
docker compose start mysql
curl --fail http://localhost:8080/actuator/health/readiness
```

结束后停止容器：

```bash
docker compose down
```

默认不加 `-v`，保留数据库卷用于重启恢复验收；只有明确需要清空数据时才删除卷。

## 11. 初学者阅读顺序

1. `pom.xml` resources：理解根目录 skills 怎样进入 JAR。
2. `Dockerfile`：理解 Docker build context 为什么必须显式 COPY。
3. `docker-compose.yml`：看 MySQL 健康依赖和容器网络服务名。
4. `DockerDeploymentConfigurationTest`：看静态门禁能证明什么。
5. `RealMySqlPersistenceIT`：看真实数据库测试和 SQL 替身测试的区别。
6. `PersistenceHealthIndicator`：理解 readiness 为什么依赖数据库。

核心原则：

> 配置文件“看起来正确”、Compose 能解析、真实数据库测试通过、完整容器健康，这四种证据强度不同，不能互相替代。
