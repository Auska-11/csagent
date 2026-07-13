# 客服 Agent 20.6：第二十批最终验收与架构收口说明

## 1. 结论

第二十批 20.1 至 20.6 已完成，可以关闭“人工转接复核闭环”目标。

这里的完成范围是：

- PendingHandoff 租户隔离；
- Memory/JDBC 人工复核查询和 CAS 状态机；
- 主记录、权威结果、追加式审计的原子写入；
- ROLE_ADMIN 列表、详情、审计和处置 API；
- 售后结果 FOUND、NOT_FOUND、UNAVAILABLE 三态查询 Port；
- README、核心架构图、简历手册与验收文档同步；
- 默认测试、JAR、真实 MySQL、Docker 镜像和完整 Compose 复验。

它不表示长期路线图的 Phase 9 已完成，也不表示系统已经完全生产级。Legacy 清理、Maven 多 Module 评估、
售后平台只读查询 API、按租户 LLM 隔离和多 Agent 进程故障注入仍是后续工作。

## 2. 20.6 为什么没有继续增加业务代码

20.1 至 20.5 已经把第二十批的生产能力做完。20.6 的职责是回答三个问题：

1. 文档描述是否与最终代码一致；
2. Memory 测试通过之外，真实 MySQL 和真实容器是否也能工作；
3. 这些证据能否在简历和面试中准确表达，不把未完成能力写成已完成。

本批只新增一个很小的部署改进：

```text
SMARTCS_AGENT_PORT=8080
```

Compose 原来固定发布 `8080:8080`。本机 8080 已被 nginx 占用，因此改成宿主机端口可配置、容器内仍固定
8080。默认值不变，本次验收使用 18080。静态 Docker 测试增加断言，防止以后又改回固定端口。

## 3. 最终架构事实

| 指标 | 最终值 |
| --- | ---: |
| Agent Skill Package | 9 个 |
| 独立业务 Workflow | 8 个 |
| 默认自动化测试 | 510 项 |
| 测试源码文件 | 119 个 |
| Java 主代码文件 | 321 个 |
| 离线评测样本 | 36 条 |
| 稳定 Capability ID | 9 个 |
| JDBC 表 | 10 张 |
| TurnOutcome | 5 类 |

10 张表包括：

- 4 张业务表；
- 1 张 Session 表；
- 1 张 Capability 幂等表；
- 4 张 Handoff 表：主记录、成功结果、Claim、人工处置审计。

核心架构图已经在 `客服Agent-01-架构设计.md` 和 README 中更新，不再把早期
`CustomerServiceSkill -> ToolManager` 写成当前生产主链路。

## 4. 文档收口内容

### 4.1 README

README 已补齐：

- Handoff Outbox、有限恢复和 ADMIN 复核能力；
- `/api/admin/pending-handoffs/**` 七个入口；
- 三态售后结果查询的当前 503 边界；
- 10 张 JDBC 表；
- 510 项默认测试；
- `SMARTCS_AGENT_PORT`；
- 20.2 至 20.6 专项文档链接；
- 不再写死某台 Windows 机器上的售后仓库路径。

### 4.2 核心架构文档

`客服Agent-01-架构设计.md` 已按最终代码重写，明确：

- Skill Package、Workflow、Runtime、Handler、Capability 和 MCP 的区别；
- Session CAS、Capability 幂等和 Handoff Outbox 解决不同一致性问题；
- PendingHandoff 的租户复合键、恢复 Worker、ADMIN 状态机和追加式审计；
- 新 Workflow、Action、Capability、MCP Provider 和售后查询 Adapter 怎样扩展；
- 当前不足和初学者阅读顺序。

### 4.3 简历与面试手册

`客服Agent-52-项目简历与面试完整手册.md` 已更新：

- 418 项改为 510 项；
- 测试源码、生产源码和表数量改为实际值；
- 人工复核入口从“未完成”改为已完成；
- 当前真实边界改为“下游没有只读幂等查询协议”；
- 30 秒、2 分钟、STAR、演示路线和速记卡统一到最终事实。

## 5. Maven 验收

### 5.1 干净全量测试

执行：

```bash
bash mvnw -q clean test
```

实测：

```text
tests=510
failures=0
errors=0
skipped=0
```

五类离线 Eval 也按既定断言生成报告：

- 路由 16 条；
- 知识召回 8 条；
- 回复质量规则 6 条；
- Agent 链路回复 3 条；
- Fake LLM-as-Judge 3 条。

Fake Judge 的 33.3% 是预设正负例的候选通过率，判断一致率为 100%，不能当成真实模型线上准确率。

### 5.2 JAR 打包

执行：

```bash
bash mvnw -q -DskipTests package
```

结果：通过。

## 6. 显式真实 MySQL 验收

启动固定版本 MySQL 后执行：

```bash
docker compose up -d mysql
bash mvnw -q -Dtest=RealMySqlPersistenceIT test
```

Surefire 报告：

```text
tests=1
failures=0
errors=0
skipped=0
time=4.182s
```

这个测试类内部覆盖：

- Session 跨 Store version CAS；
- Capability 幂等并发、租约和结果回放；
- PendingHandoff 租户复合键和 Claim；
- 人工 retry、confirm、close；
- 人工决定与迟到 Worker 的正反竞态；
- 主记录、成功结果和审计原子事务；
- audit INSERT 注入失败时整体回滚；
- 同毫秒审计 keyset 分页。

默认 `mvn test` 不执行这个 IT，所以文档将“510 项默认测试”和“1 项显式真实 MySQL IT”分别记录。

## 7. Docker 镜像验收

环境：

```text
Docker Client/Server: 24.0.7
Docker Compose: v2.23.3-desktop.2
```

执行：

```bash
SMARTCS_AGENT_PORT=18080 docker compose build smart-cs-agent
```

结果：

```text
image: smart-cs-agent:latest
id: sha256:6f33ac2cfa911435a20e7b10771383f360e35a7f066b3a2e3737ba6dc3cdfb11
size: 360231617 bytes
Docker Maven build: BUILD SUCCESS
```

运行日志逐个加载以下 9 个 Skill：

```text
customer-service
escalation
order-query
policy-qa
product-advisory
return-apply
return-query
user-profile
warranty-query
```

加载来源是容器 `/app/app.jar` 内的 `BOOT-INF/classes/skills/**/manifest.yaml`，不是宿主机目录偶然可见。

## 8. 完整 Compose 验收

启动：

```bash
SMARTCS_AGENT_PORT=18080 docker compose up -d
```

三个服务实际状态：

```text
smart-cs-agent   healthy   18080 -> 8080
smart-cs-mysql   healthy   3307 -> 3306
smart-cs-qdrant  running   6333 -> 6333
```

初始健康探针：

```text
liveness=200
readiness=200
```

数据库中实际确认 10 张目标表全部存在。

## 9. RBAC 与 API smoke

从 Demo issuer 取得短期 JWT 后调用：

```text
GET /api/admin/pending-handoffs?size=1
```

结果：

```text
仅 CUSTOMER 身份：403
包含 ADMIN 身份：200
```

这证明容器运行时使用真实 Spring Security 链，而不是只验证 Controller 单元测试。

## 10. Agent 重启与会话持久化

第一轮：

```text
conversationId: phase-20-6-persist-ok
用户：我要退货
Agent：请提供需要退货的订单号
MySQL Session version: 1
```

只重启 Agent 容器，MySQL 不重启、不清空：

```bash
SMARTCS_AGENT_PORT=18080 docker compose restart smart-cs-agent
```

重启后在同一 conversationId 输入当前用户拥有的 `ORD-001`：

```text
Agent：订单验证通过。请简单说明退货原因……
workflow: return_apply
status: WAITING_USER
MySQL Session version: 2
totalMessageCount: 4
```

如果 Session 没有恢复，`ORD-001` 会被当成一条新请求重新路由；现在它继续执行退货 Workflow，形成真实
JDBC 持久化与 Agent 重启恢复证据。

验收中还试过 `ORD-004`。它属于 USER-002，而 JWT 是 USER-001，所以被所有权校验拒绝。这是预期安全行为，
不能误写为持久化失败。

## 11. 数据库掉线与恢复

停止 MySQL 后：

```text
liveness=200
readiness=503
```

解释：

- JVM 和 Web Server 仍存活，所以 liveness 不应触发无意义重启；
- JDBC 依赖不可用，实例不适合接收业务流量，所以 readiness 必须为 503。

重新启动 MySQL 后，第 5 次两秒轮询得到：

```text
mysql=healthy
readiness=200
```

Agent 没有重启，已保存的会话仍存在。

## 12. 清理结果

验收后执行：

```bash
SMARTCS_AGENT_PORT=18080 docker compose down
```

最终状态：

- 三个临时容器已停止并删除；
- Compose 临时网络已删除；
- `smart-cs-agent:latest` 镜像保留；
- `csagent_mysql_data` 卷保留；
- `csagent_qdrant_data` 卷保留；
- 没有执行 `docker compose down -v`。

## 13. 当前真实边界

20.6 没有改变 20.5 的外部契约结论：

- 售后平台支持相同 POST 的创建幂等；
- 售后平台没有公开按租户和 Idempotency-Key 的只读查询 API；
- Agent 的 AGENT 服务身份不能使用管理列表或详情绕过；
- 默认 `SMARTCS_AFTER_SALES_RESULT_QUERY_MODE=unavailable`；
- ADMIN `reconcile` 当前安全返回 503；
- 受信任管理员可以外部核对后使用 `confirm`；
- 未来要先补下游契约，再实现 HTTP 查询 Adapter。

不能因为第二十批完成，就把“可替换 Port 已完成”夸大成“真实远程查询已接通”。

## 14. 目标关闭判断

第二十批目标满足关闭条件：

- [x] 20.1 租户隔离与复合键；
- [x] 20.2 人工复核查询和 CAS 状态机；
- [x] 20.3 追加式审计和原子事务；
- [x] 20.4 ROLE_ADMIN HTTP API；
- [x] 20.5 售后三态查询 Port 与契约审计；
- [x] 20.6 文档、全量测试、JAR、真实 MySQL、镜像与 Compose 总验收；
- [x] 临时容器停止删除，数据卷按约定保留；
- [x] 本批准备本地提交，不推送远端。

第二十批关闭后不再以“继续”自动扩张范围。若后续开启 Phase 9，应先重新设定独立目标，明确 Legacy 清理
门槛和是否真的需要 Maven 多 Module。
