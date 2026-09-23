# 客服Agent-78-服务器部署手册

> 2026-09-05 凌晨整理。目标：把 Mac 本地的「Agent + MySQL + 闲鱼侧车」整套搬到阿里云 ECS，
> 成为 7×24 在线的正式运行态；Mac 侧退役为开发机。
> 密钥纪律：`.env`（SenseNova/SiliconFlow 密钥）与 `sidecar/xianyu/.env`（闲鱼 cookie）
> 只经 scp 传输、落盘 `chmod 600`，一律不入库、不打印、不写进本文档。

## 0. 服务器档案（已核实）

| 项 | 值 |
| --- | --- |
| 地址 | 阿里云杭州 ECS `121.43.193.142`（`ssh myserver`，密钥登录 root） |
| 规格 | 2C / 3.4G 内存 / 40G 盘，Ubuntu 24.04 |
| Docker | 29.1.3 + Compose 2.40.3（apt 安装，2026-09-05 凌晨完成） |
| 镜像加速 | /etc/docker/daemon.json 已配 m.daocloud / 1ms / rat.dev 三镜像源 |
| 预拉镜像 | mysql:8.4.0、qdrant/qdrant:v1.9.7、python:3.11-slim、eclipse-temurin:17-jre |
| 部署目录 | `/opt/csagent` |

内存预算（3.4G）：JVM 封顶 768m（JAVA_OPTS，compose 已设）+ MySQL ~450m + Qdrant ~200m（local 模式仅闲置待命）+ 侧车 ~100m + 系统 ~300m ≈ 2.2G，留有余量。

## 1. 部署物清单（均已入库）

| 文件 | 作用 |
| --- | --- |
| `Dockerfile.prebuilt` + `docker-compose.prebuilt.yml` | **主路径**：开发机打包 JAR（pom 默认把 `skills/` 打进 classpath、frontend profile 把 React 产物叠进 static/），服务器只做运行时镜像（temurin:17-jre），绕开国内网络下 node/maven 重建 |
| `Dockerfile` | 备选：服务器多阶段全量构建（需拉 maven/node 镜像，慢且脆，留作兜底） |
| `docker-compose.yml` | mysql/qdrant 端口**只绑 127.0.0.1**；agent 全量环境变量（含 `SMARTCS_XIANYU_*` 与 `JAVA_OPTS`）；agent 挂命名卷 `agent_data:/app/data`（商品事实覆盖层等落盘，容器重建不丢）、侧车挂 `sidecar_state:/app/data`（会话映射与人工模式状态）；侧车服务 `smart-cs-sidecar` 挂在 `--profile xianyu` 下，默认不启动 |
| `sidecar/xianyu/Dockerfile` + `.dockerignore` | python:3.11-slim 运行侧车；`.env` 被 ignore，cookie 不会进镜像层 |
| `scripts/deploy/sync-to-server.sh` | rsync 源码+JAR 到服务器（排除密钥/构建产物/虚拟环境，`--delete` 不动远端被排除文件） |
| `scripts/deploy/export-mysql.sh` / `import-mysql.sh` | MySQL 迁移对：本机容器导出 gzip → 服务器容器导入 |

数据说明：`OrderRepository` 启动期 upsert 播种演示订单（ORD-001..004 + 退货单 RET-001），**空库开箱即用**；迁移仅当要保留 Mac 上的真实会话历史/记忆/转人工工单时才做（§2 步骤 4b）。

## 2. 首次部署（开发机 → 服务器）

1. **打包**（Mac，需 node；产物 `target/smart-cs-agent-0.0.1-SNAPSHOT.jar`）：
   ```bash
   sh mvnw -DskipTests package
   ```
2. **同步代码与 JAR**：
   ```bash
   sh scripts/deploy/sync-to-server.sh          # 默认 myserver:/opt/csagent
   ```
3. **密钥**（一次性；scp 走 SSH 加密通道）：
   ```bash
   scp .env myserver:/opt/csagent/.env
   ssh myserver 'chmod 600 /opt/csagent/.env'
   ```
   然后在服务器上编辑 `/opt/csagent/.env`：
   - `SMARTCS_SECURITY_DEMO_ISSUER_ENABLED=false`（公网形态先关，见 §6 决策；2026-09-07 起 compose 默认即 false，不设也关闭）；
   - `SMARTCS_SECURITY_JWT_SECRET=` 改为 `openssl rand -base64 48` 新值（旧 token 全部失效，无影响）。**2026-09-07 起为硬性要求**：compose 将其声明为必填变量，`.env` 缺失时 `up -d` 直接报错拒绝启动，不再回退开发密钥；“Demo 签发开 + 仓库公开开发密钥”组合应用侧也会启动失败；
   - 删除/注释 `SMARTCS_JDBC_URL` 行——容器内走 compose 默认 `jdbc:mysql://mysql:3306/...`（.env 里那行是 Mac 宿主机形态 `127.0.0.1:3307`，带 `&`，注意别用 shell source 编辑）；
   - MySQL 口令：**空库路径**可改强口令（`SMARTCS_MYSQL_PASSWORD`/`ROOT_PASSWORD` 仅首次初始化数据卷时生效）；**迁移路径**必须保持与 Mac 一致（账号在 dump 里）；
   - `SMARTCS_XIANYU_*` 先不动（默认关闭），切换闲鱼时再开（§4）。
4. 启动（默认空库；如需保留 Mac 会话历史，先做 4b 迁移再 up）：
   ```bash
   ssh myserver
   cd /opt/csagent
   docker compose -f docker-compose.yml -f docker-compose.prebuilt.yml up -d --build
   ```
   **4b（可选）迁移**：Mac 上 `sh scripts/deploy/export-mysql.sh` → `scp target/deploy/mysql-*.sql.gz myserver:/tmp/` → 服务器 `gunzip -c /tmp/mysql-*.sql.gz | docker exec -i smart-cs-mysql mysql -usmartcs -p"$(grep '^SMARTCS_MYSQL_PASSWORD=' /opt/csagent/.env | cut -d= -f2)" "$DB"`（库名取 .env `SMARTCS_MYSQL_DATABASE`，默认 smart_cs_agent）。注意：必须**首次 up 初始化前**导入或导入后重启 agent。

## 3. 部署验证清单

```bash
ssh myserver
docker compose -f docker-compose.yml -f docker-compose.prebuilt.yml ps   # 含 --profile xianyu 时四容器 healthy/running
docker logs smart-cs-agent 2>&1 | grep -E "Started|skill" | tail        # 启动完成 + 10 个技能包
curl -s localhost:8080/actuator/health                                   # {"status":"UP"}
docker exec -it smart-cs-mysql mysql -usmartcs -p -e "select order_id,status from smart_cs_agent.cs_order;"  # ORD-001..004 已播种（表名 cs_order；中文状态值加 --default-character-set=utf8mb4 看）
```

业务冒烟（模板链路，不耗 LLM 额度）：浏览器经 SSH 隧道访问（§6）后用演示身份问「我的订单 ORD-001 到哪了」→ 应回物流单号；或：

```bash
curl -s -X POST localhost:8080/api/auth/demo/tokens -H 'Content-Type: application/json' \
  -d '{"tenantId":"default"}'   # demo issuer 关闭时此端点 404——属预期
```

## 4. 闲鱼侧车切换上线（Mac → 服务器）

同一卖家 cookie 只允许一处登录 WebSocket，切换即互踢，按序执行：

1. Mac 停侧车（当前 `ps aux | grep 'sidecar/xianyu'` 找 PID，kill）；
2. 传 cookie：
   ```bash
   scp sidecar/xianyu/.env myserver:/opt/csagent/sidecar/xianyu/.env
   ssh myserver 'chmod 600 /opt/csagent/sidecar/xianyu/.env'
   ```
3. 服务器 `.env` 开通道：`SMARTCS_XIANYU_ENABLED=true`；`SMARTCS_XIANYU_SERVICE_TOKEN=` 填侧车 `.env` 里的 `CS_AGENT_TOKEN`（两边必须一致，≥32 字节）；
4. 起侧车（连同 agent 环境变量变更一起生效）：
   ```bash
   cd /opt/csagent
   docker compose -f docker-compose.yml -f docker-compose.prebuilt.yml --profile xianyu up -d --build
   docker logs -f smart-cs-sidecar   # 看到 /reg 注册成功、无心跳超时即在线
   ```
5. 小号验证：商品 `1079979582985` 会话发「你好」→ 应回小智开场白；再按 doc-76 §6.1 压测表抽查。
6. 回退：`docker compose ... stop smart-cs-sidecar` → Mac 重启 `sidecar/xianyu/.venv/bin/python main.py`。

## 5. 日常更新流程

```bash
# Mac
sh mvnw -DskipTests package && sh scripts/deploy/sync-to-server.sh
# 服务器（只重建 agent 容器，mysql/qdrant/侧车不动）
cd /opt/csagent && docker compose -f docker-compose.yml -f docker-compose.prebuilt.yml up -d --build
```

知识文档更新同上（知识索引启动期一次性构建，需 agent 重启）；闲鱼商品快照草稿在服务器 `data/xianyu-items/`，审校入 `resources/knowledge/product/` 后走同一更新流程。

## 6. 安全清单与 8080 暴露决策

- 安全组：**只开 22**（SSH）。MySQL/Qdrant compose 已绑 127.0.0.1，公网不可达；侧车是**出站** WebSocket，不需要任何入站端口。
- Web UI（React）访问走 SSH 隧道，本机浏览器开 `http://localhost:8080`：
  ```bash
  ssh -N -L 8080:localhost:8080 myserver
  ```
- 8080 是否对公网开放的三选项：

| 选项 | demo issuer | 后果 |
| --- | --- | --- |
| A（当前默认）不开 8080，隧道访问 | 可开（`true`） | 零公网面；UI 经隧道可用；手机浏览器不便 |
| B 开 8080 | **必须 false** | 公网可打开页面但**无法登录**（唯一登录端点即 demo tokens，见 DemoAuthController），仅调试用 |
| C 开 8080 | true | 任何人都可为演示身份签 JWT 并触发会话/副作用——演示项目可接受但不作默认，风险已知 |

- JWT 密钥服务器侧独立生成（§2 步骤 3）；`.env` 两处均 600 权限；`target/eval-reports/` 与密钥文件永不入库。
- 升级口诀：改 `.env` 后 `docker compose ... up -d` 即重建生效；改代码走 §5。

## 7. 故障排查速查

| 症状 | 处置 |
| --- | --- |
| agent 容器 unhealthy | `docker logs smart-cs-agent`；常见是 MySQL 未就绪（healthcheck 会自动等）或内存不足（`free -m` 确认，必要时 JAVA_OPTS 降到 640m） |
| LLM 回复变慢/降级 | SenseNova 免费档限流：看日志 `LLM_PROVIDER`/超时字样；确认没有别的进程共享额度 |
| 侧车无回复 | `docker logs smart-cs-sidecar`：心跳超时=闲鱼断连（重启容器）；4xx 注册失败=token 不一致或 cookie 失效（Mac 浏览器重新抓 cookie 更新 `.env`） |
| 买家消息但 agent 无日志 | 侧车↔agent 断链：`docker logs smart-cs-sidecar | grep CS_AGENT`，容器网络内应为 `http://smart-cs-agent:8080` |
| 磁盘涨 | `docker system df`；`data/xianyu-items/` 草稿与 `target/deploy/` 备份可清 |

## 8. SLA 售后平台共部署（/opt/sla，2026-09-09 上线）

SLA 平台（after-sales-sla-platform）与客服 Agent 共存在同一台 ECS 上。**SLA 仓库保持只读**：SLA 侧不改任何文件，全部编排物（compose/Dockerfile/env 模板/同步脚本）放在 csagent 仓库 `deploy/sla/` 与 `scripts/deploy/` 下。

### 8.1 拓扑与端口

| 组件 | 目录/项目 | 网络 | 端口（全部只绑 127.0.0.1） |
| --- | --- | --- | --- |
| 客服 Agent 栈 | `/opt/csagent`（compose 项目默认名） | `csagent_default` | agent 8080、mysql 3307、qdrant 6333 |
| SLA 栈 | `/opt/sla`（compose 项目名 `sla`） | `sla_default` + 外部网络 `csagent_default` | app 8081、sla-mysql 3308、sla-rabbitmq 5673 |

跨栈调用全部走容器内网（公网零暴露）：

- Agent → SLA：`SMARTCS_HANDOFF_SLA_V1_BASE_URL=http://after-sales-app:8081`（sla-v1 建单 + 服务账号登录）
- SLA → Agent 回调：`http://smart-cs-agent:8080/api/integrations/v1/sla-results`（结果投递，SLA 侧 5s 调度器）

SLA 栈为 3.4G 内存做的裁剪：只部署 `sla-mysql`（mysql:8.4）+ `sla-rabbitmq`（rabbitmq:3.13，无管理 UI）+ app；redis/elasticsearch 走 profile 默认不启动（`app.redis.enabled=false`、`elasticsearch.enabled=false`），JVM `-Xmx512m -Xms192m`。共增内存约 1.1G，部署后 `free -m` 约 2300 已用 / 1195 可用（swap 365M），可接受。

### 8.2 部署物（均在 csagent 仓库，已入库）

| 文件 | 作用 |
| --- | --- |
| `deploy/sla/Dockerfile.prebuilt` | SLA 运行时镜像（temurin:17-jre + curl healthcheck），JAR 从 SLA 仓库本地打包后 rsync |
| `deploy/sla/docker-compose.yml` | 三服务编排；`app` 同时挂 `sla_default` 与外部 `csagent_default`；SLA_* 前缀变量全部来自 `/opt/sla/.env` |
| `deploy/sla/env.example` | 变量名模板（不含值）；与 Agent 侧 `SMARTCS_HANDOFF_SLA_V1_*` 的取值一致性要求已注明 |
| `scripts/deploy/sync-sla-to-server.sh` | rsync 编排物（`--exclude .env`，远端密钥不会被 `--delete` 清掉）+ JAR 到 `/opt/sla` |

### 8.3 部署/更新流程

```bash
# Mac：SLA 仓库本地打包（仓库只读，只构建不修改）
cd ~/after-sales-sla-platform && ./mvnw -DskipTests package
sh scripts/deploy/sync-sla-to-server.sh   # 编排物 + JAR → /opt/sla
# .env 手工准备（变量名见 env.example），scp 后 chmod 600（同 §0 密钥纪律）
# 服务器
cd /opt/sla && docker compose -p sla up -d --build
```

Agent 侧开启 sla-v1 模式：`/opt/csagent/.env` 追加 7 个变量（**仅变量名**）：`SMARTCS_HANDOFF_MODE=sla-v1`、`SMARTCS_HANDOFF_SLA_V1_BASE_URL`、`_USERNAME`、`_PASSWORD`、`_SELLER_ACCOUNT_ID`、`_CALLBACK_TOKEN`、`_CALLBACK_TENANT_ID`——前 6 项取值必须与 SLA 侧 `/opt/sla/.env` 的 `SLA_APP_INTEGRATION_V1_JSON` 中 sla-agent 账号配置一致（回调 token 同理反向核对），随后 `docker compose ... up -d` 重建 agent。

卖家工作台访问走 SSH 隧道：`ssh -N -L 8081:localhost:8081 myserver` → 浏览器 `http://localhost:8081`，管理员账号见 `SLA_APP_AUTH_BOOTSTRAP_USERNAME`（口令在服务器 `.env`，不入库不打印）。

### 8.4 两个已排掉的坑（复装必读）

1. **mysql:8.4 原生密码插件**：2026-09 之后的 mysql:8.4 镜像默认不加载 `mysql_native_password`，SLA 的 Flyway/mysql-connector-j 会报 `Plugin 'mysql_native_password' is not loaded`。处置：sla-mysql 启动命令加 `--mysql-native-password=ON`（compose 已内置）。
2. **跨网络服务别名冲突**：SLA compose 最初沿用上游服务名 `mysql`/`rabbitmq`，而 `app` 同时挂在 `csagent_default` 上——Docker DNS 把 `mysql` 解析到了**客服栈的 smart-cs-mysql（172.18.0.2）**，症状是 app 连"自己的"数据库报认证插件错误、反复崩溃重启，极易误判为坑 1。处置：跨共享网络的服务名必须全局唯一，已改名 `sla-mysql`/`sla-rabbitmq`（compose 已内置；复装勿改回）。改名重建前需 `docker rm -f` 旧容器，命名卷数据不受影响。

### 8.5 上线冒烟证据（2026-09-09，合成买家 90003001，未打扰任何真实买家）

全链路一次通过：买家消息触发商品咨询核实 handoff → 回复「已经请卖家本人实际核实（记录编号：AS-1）」→ SLA 库 `work_items` id=1（PRODUCT_CHECK，requestId `wi-v1-`+SHA256 幂等）建单、`tickets` id=1 自动指派 seller-admin-001 → 卖家置 PROCESSING→RESOLVED 并提交结果（CONFIRMED v1，内容「耳机实测蓝牙连接正常，续航约18小时」）→ `result_delivery_tasks` 单行 `sla-outbox-4/SENT`（回调已被 agent 确认）→ **Agent 侧** `cs_sla_result_inbox` 恰一行 `sla-outbox-4/APPLIED`（去重幂等）+ `cs_sla_work_item_result` v1 快照 + `cs_sla_work_item_request` 关联行。人工接管闭环：handoff 后买家追问按设计返回 409 `XIANYU_CONVERSATION_PAUSED`；模拟卖家经侧车 `seller-messages`（`resumeBot=true`）发核实结论后机器人恢复，买家再问「续航到底多少小时」→「续航约18小时，蓝牙连苹果手机实测也正常，可以放心～」（LLM `degraded:false`，2.3s），与卖家核实结果一致。

**待验/已知边界**：真实买家经闲鱼真机的全链路待侧车 cookie 修复后验证；SenseNova 免费档限流仍可能偶发 429 降级；RabbitMQ 管理 UI 未部署（可选 `rabbitmq:3.13-management`）；`smart-cs-sidecar` 重启循环为既有 cookie 过期问题，与本次部署无关。

### 8.6 侧车出口 IP 问题与待定方案（2026-09-09 深夜，风控排查结论）

排查过程见 §9 动态记录 2026-09-09（深夜·风控排查）行。核心事实：curl_cffi（Chrome TLS 指纹）+ 最新有效 cookie 在 Mac（家庭宽带 IP）可通过 `mtop.taobao.idlemessage.pc.login.token`，在服务器（阿里云 ECS 机房 IP）仍被 `RGV587_ERROR::SM` 拒——闲鱼风控在 TLS 指纹之外对机房 IP 段叠加了 IP 维度降权。侧车代码改造已完成并部署进镜像（容器内已验证生效），唯一卡点是出口 IP。三个候选方案：

1. **住宅代理出口（服务器侧车不变）**：侧车访问闲鱼走住宅/家宽代理（curl_cffi 原生支持 `proxies`，加 `XIANYU_PROXY` 环境变量即可接入）。优点：部署拓扑不变、7×24 不依赖 Mac 开机；代价：第三方代理按流量计费、代理质量决定稳定性，且出口 IP 变动本身可能触发风控。
2. **侧车回退 Mac 本地运行**：侧车在 Mac 上跑（`.venv` 或本地容器），`CS_AGENT_URL` 指向服务器 agent 公网/隧道入口，Java/SLA 栈全部留在服务器。优点：零新增成本、出口即验证过可过的家庭宽带；代价：Mac 需常开、断电/合盖即掉线（消息漏接，恢复后重连）。
3. **暂不上线闲鱼侧车**：Agent/SLA 栈照常（合成买家冒烟已过），真机接入推迟到前两方案之一落地。

**2026-09-10 起当前状态：方案 2（Mac 本地运行）生效**，服务器侧车容器保持 stopped。Mac 侧运行方式：

```bash
# 1) SSH 隧道：本地 18080 → 服务器 agent 8080（8080 不暴露公网；Mac 本地 8080 被 nginx 占用故选 18080）
ssh -f -N -L 18080:localhost:8080 -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -o ExitOnForwardFailure=yes myserver

# 2) 侧车进程（BRIDGE 模式；.env 已由服务器拉取改造：CS_AGENT_URL=http://127.0.0.1:18080）
cd ~/csagent/sidecar/xianyu && .venv/bin/python main.py
```

- `.env`（gitignore）含 COOKIES_STR（有效 cookie）、CS_AGENT_TOKEN（与服务器 `SMARTCS_XIANYU_SERVICE_TOKEN` 同值）、`SIDECAR_MODE=BRIDGE`；cookie 刷新经 `update_env_cookies` 写回**本地** .env（服务器 .env 不再被刷新，切回方案 1 时需重新同步）。
- 验证在线：日志出现「侧车已连接（模式: BRIDGE，卖家ID: 2223022576689）」；隧道探活 `curl http://127.0.0.1:18080/actuator/health` 应返回 UP。
- 已知边界：Mac 合盖/休眠/断网即掉线，期间买家消息漏接（进程自带 5s 退避重连，恢复后自动回来）；隧道与侧车进程都不随 Mac 重启自启。后续如需 7×24，回到方案 1（住宅代理）。

## 9. 动态记录

| 日期 | 事项 |
| --- | --- |
| 2026-09-05 | 凌晨完成服务器准备（Docker/加速/预拉镜像）与全部部署物（本表 §1）；手册创建。首次部署与闲鱼切换待基线跑批完成后执行 |
| 2026-09-05 | **首次部署 + 闲鱼切换一次完成（15:52 起 7×24 运行态）**。流程：Mac 打包（6s，含 skills×10 与 React UI）→ sync-to-server → 双 .env scp + chmod 600 → 服务器侧改造（demo issuer 关、JDBC_URL 注释走容器内网、`openssl rand` 新 JWT 密钥与 hex 强 MySQL 口令——APP 口令双写 `SMARTCS_MYSQL_PASSWORD`+`SMARTCS_JDBC_PASSWORD` 保持一致、`SMARTCS_XIANYU_ENABLED=true`、服务令牌经 stdin 管道由侧车 `CS_AGENT_TOKEN` 换名注入不落终端）→ Mac 侧车 kill（cookie 单点登录让位）→ `--profile xianyu up -d --build`。**两个首跑缺陷当场修掉**：① `.dockerignore` 整目录排除 `target` 使 `Dockerfile.prebuilt` 的 `COPY target/*.jar` 必失败——改 `target/*` + `!target/smart-cs-agent-0.0.1-SNAPSHOT.jar` 放行单个 JAR；② sync 脚本对不存在的远端 `target/` 传单文件报 `change_dir failed`——补 `ssh mkdir -p`。§3 手册错表名 `orders`→`cs_order`、技能包 8→10 一并勘误。验证：四容器 healthy/running（agent 451M/768M 上限、mysql 462M、qdrant 11M、侧车 27M，系统余 1.9G）；`/actuator/health` UP（db/persistence 均 UP）；11 张表 + ORD-001..004/RET-001 播种、utf8mb4 中文完好；侧车 token→/reg→BRIDGE 在线（卖家 2223022576689），45 分钟窗口零超时零异常。**遗留**：买家真机消息验证需用户小号发消息（无法自主完成）；Web UI 需 SSH 隧道（`ssh -N -L 8080:localhost:8080 myserver`） |
| 2026-09-06 | **买家真机消息验证通过（00:17，部署后 8.5h）**。小号买家（3579903100）在商品 1079979582985 会话发「你好」：侧车 0.7s 内完成 Java 会话注册（`USER-XY-a7f5a18f`，商品知识草稿首次落盘 `data/xianyu-items/1079979582985.md`），5s 内回复「您好！我是小智…」发回闲鱼，首条免责话术按设计追加；会话已入 `cs_agent_session`（jdbc 持久化生效）。**运行观察**：8.5h 内仅 1 次 WebSocket 断连（00:15 Session 过期重试环 7s 自愈），其余为每小时 token 刷新后的例行重注册；内存 agent 480M/侧车 40M 稳定。**已知边界**：容器内 cookie 经 env 注入而非 .env 文件，token 刷新后的新 cookie 无法写回（`update_env_cookies` 警告）——进程内存内自持可运行，但**容器重建仍用初始 cookie**，若未来重启报 Session 过期需重抓 cookie 更新 `.env` 后 `up -d`。 |
| 2026-09-07 | **安全与并发四项修复落地（详见 doc-76 当批记录）**：① 退货单号改“毫秒时间戳+进程内序列”base36，`ReturnRepository.create()` 插入语义 + `uk_cs_return_order_id` 唯一键幂等迁移（跨订单并发双成功、同订单第二笔被 DB 查重拒绝）；② 会话锁改 256 条纹固定数组，排队等待计入 `chatWithTrace` 入口起的 30s 预算，超时快速失败 `409 CONVERSATION_BUSY`；③ tenantId 贯穿订单/退货业务表（可信 Principal 租户，跨租户拒绝测试）；④ **Demo issuer 三处默认改关 + compose 必填 `SMARTCS_SECURITY_JWT_SECRET` + 启动期“Demo+开发密钥”守卫**，本地演示叠加 `docker-compose.demo.yml`。服务器 `.env` 在 09-05 已含私有密钥与 `DEMO_ISSUER_ENABLED=false`，本次升级无需改 `.env`，`up -d` 行为不变。 |
| 2026-09-08（深夜） | **数据卷挂载上线（评审三轮问题 1）**：`docker-compose.yml` 给 agent 加 `agent_data:/app/data`、侧车加 `sidecar_state:/app/data`。此前边界"compose 重建清空容器盘→商品事实覆盖层丢失"就此关闭——服务器实测 `--force-recreate` 重建后 facts 保持 stock=0、重建后新买家议价直接命中售出话术；侧车命名卷保住 `sidecar-state.json`（chat_id→买家映射 + 人工模式），重启后卖家句号/人工消息仍同步 Java。注意：首次应用该 compose 变更时旧容器可写层里的覆盖层不会自动搬进新卷（首次 `up -d` 后由侧车 TTL 重推或 facts 推送重建）。 |
| 2026-09-09 | **SLA 售后平台共部署上线（§8）**：`/opt/sla` 三容器（sla-mysql/sla-rabbitmq/app）healthy，agent 切 `sla-v1` 模式 healthy；跨栈容器内网互通（agent→after-sales-app:8081，回调→smart-cs-agent:8080）。部署期排掉两坑：mysql:8.4 默认不加载 mysql_native_password（加 `--mysql-native-password=ON`）、共享网络上 `mysql` 服务别名解析到客服栈 smart-cs-mysql（服务名改 sla-mysql/sla-rabbitmq 全局唯一）。冒烟全链路见 §8.5（AS-1 建单→卖家结果→sla-outbox-4 SENT→agent inbox APPLIED→恢复机器人后买家追问命中核实结论）；合成买家 90003001，零真实买家打扰。内存 2304/3499 已用、可用 1195M。服务器临时备份 `/opt/csagent/.env.bak-sla` 含旧 .env 副本，下次 `sync-to-server.sh --delete` 会自动清掉（其内容已被现 .env 取代）。 |
| 2026-09-09（深夜·风控排查） | **侧车风控根因定位与 curl_cffi 改造：根因是 TLS 指纹拦截而非 cookie 失效，服务器侧车暂无法上线（IP 维度拦截，待定方案）**。背景：侧车 09-08 起在服务器上 `RGV587_ERROR::SM`（"被挤爆啦"）崩溃循环，期间 5 次 cookie 更新均无效。逐项隔离定位（同一 cookie 控制变量）：① 更新到浏览器验证仍有效的最新 cookie（含/不含 x5sec 均试）→ 服务器 requests 仍拒；② **同一 cookie 从 Mac（家庭宽带 IP）以 Python requests 发同样请求 → 仍被拒**，证明不是服务器 IP/cookie 问题；③ 同一 cookie 从 Mac 以 `curl_cffi`（`impersonate="chrome"`，Chrome TLS 指纹）→ **通过** `mtop.taobao.idlemessage.pc.login.token`。结论：阿里 SM 风控按 TLS 指纹拦截，Python `requests` 握手特征直接拒绝（cookie 有效也一样），真 Chrome 放行。改造（本地完成、测试 29/29）：`XianyuApis.py` 会话改 `curl_cffi`（`XIANYU_IMPERSONATE` 环境变量可覆盖，未安装时回退 requests 供离线测试）；`requirements.txt` 加 `curl_cffi==0.16.3`；CookieJar 协议差异统一走新增 `_cookie_pairs`（从底层 stdlib jar 取 Cookie 对象，绕开 curl_cffi 同名跨域 `get()` 抛 `CookieConflict`）；详见 `sidecar/xianyu/PROVENANCE.md`。**服务器部署结果（如实）**：镜像已重建（容器内确认 `curl_cffi 0.16.3` 生效、session 为 `curl_cffi.requests.session`）、最新有效 cookie 已注入 `.env`（只替换 COOKIES_STR 行，chmod 600），但服务器（阿里云 ECS IP）单发验证仍 `RGV587_ERROR::SM`——**同一 cookie + Chrome 指纹，Mac（家庭宽带）过、服务器（机房 IP）拒**。curl_cffi 生效已确认，排除代码问题，指向闲鱼风控对数据中心/机房 IP 段在 TLS 指纹之外叠加了 IP 维度降权。**侧车保持停止**（避免重试循环刺激风控）；Agent/SLA 栈不受影响。待定方案（三选一，见 §8.6）：服务器侧车走住宅代理出口、侧车回退 Mac 本地运行（服务器 agent 不变）、或暂不上线闲鱼侧车。 |
| 2026-09-10 | **侧车切 Mac 本地运行（§8.6 方案 2），BRIDGE 上线成功**：SSH 隧道（本地 18080→服务器 agent 8080，8080 不暴露公网）+ 本地 `.venv` 跑侧车（`.env` 自服务器拉取改造：`CS_AGENT_URL=http://127.0.0.1:18080`，cookie/服务令牌不变）。验证：token 获取成功（首次 `FAIL_SYS_TOKEN_EXOIRED` 换 token 后即过，正常现象）→ `/reg` 注册完成 → **BRIDGE 在线（卖家 2223022576689）**，30s 心跳静默稳定；服务令牌经隧道探活 agent 端点 404（鉴权通过+链路通，查询会话不存在属预期）。服务器侧车容器保持 stopped。真机 E2E（小号对商品 1079979582985 发消息）待用户执行。 |
