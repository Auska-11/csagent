# 客服 Agent 真实 MySQL 与 Docker 编排验收说明

## 1. 本批解决什么问题

此前 JDBC 测试主要使用 SQL 语义替身，Dockerfile 也只复制 `src`，导致本地 Maven JAR 含有 Skill Package，但容器构建阶段可能缺少根目录 `skills/`。

本批完成两部分工作：

1. 使用真实 MySQL 8.4.0 容器验证 Session、Capability 幂等和 Outbox 数据库语义。
2. 修复 Dockerfile/Compose 的 Skill、MySQL、依赖顺序和健康检查配置。

需要明确：真实 MySQL 验收已经实际运行通过；Agent 镜像构建因本地执行审批额度不足未能运行，因此完整 Agent 容器 readiness 仍是待验收项。

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
Container status: healthy
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

本批默认测试共 396 项，0 失败、0 错误、0 跳过。

## 9. 尚未完成的 Docker 证据

本次执行 `docker compose build smart-cs-agent` 时，桌面执行审批系统因额度不足拒绝了构建操作。因此以下事项不能标记完成：

1. Agent 镜像真实构建成功。
2. 容器 JAR 内 9 个 Skill Package 的实际 `jar tf` 检查。
3. MySQL、Qdrant、Agent 三个服务完整启动。
4. Agent 容器 readiness 变为 healthy。
5. 停止 MySQL 后 readiness 变为 unhealthy，恢复 MySQL 后重新 healthy。
6. Agent 容器重启后恢复 Session 和 Outbox。

静态配置测试通过不等于这些运行时证据已经存在。

## 10. 下一次完整容器验收命令

获得 Docker 构建授权后执行：

```bash
docker compose build smart-cs-agent
docker compose up -d
docker compose ps
docker exec smart-cs-agent sh -c "jar tf app.jar | grep 'BOOT-INF/classes/skills/.*/manifest.yaml'"
curl --fail http://localhost:8080/actuator/health/readiness
```

然后执行数据库掉线与恢复测试，并在结束后使用：

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
