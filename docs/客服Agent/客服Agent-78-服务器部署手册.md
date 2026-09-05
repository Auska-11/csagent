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
| `docker-compose.yml` | mysql/qdrant 端口**只绑 127.0.0.1**；agent 全量环境变量（含 `SMARTCS_XIANYU_*` 与 `JAVA_OPTS`）；侧车服务 `smart-cs-sidecar` 挂在 `--profile xianyu` 下，默认不启动 |
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
   - `SMARTCS_SECURITY_DEMO_ISSUER_ENABLED=false`（公网形态先关，见 §6 决策）；
   - `SMARTCS_SECURITY_JWT_SECRET=` 改为 `openssl rand -base64 48` 新值（旧 token 全部失效，无影响）；
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

## 8. 动态记录

| 日期 | 事项 |
| --- | --- |
| 2026-09-05 | 凌晨完成服务器准备（Docker/加速/预拉镜像）与全部部署物（本表 §1）；手册创建。首次部署与闲鱼切换待基线跑批完成后执行 |
| 2026-09-05 | **首次部署 + 闲鱼切换一次完成（15:52 起 7×24 运行态）**。流程：Mac 打包（6s，含 skills×10 与 React UI）→ sync-to-server → 双 .env scp + chmod 600 → 服务器侧改造（demo issuer 关、JDBC_URL 注释走容器内网、`openssl rand` 新 JWT 密钥与 hex 强 MySQL 口令——APP 口令双写 `SMARTCS_MYSQL_PASSWORD`+`SMARTCS_JDBC_PASSWORD` 保持一致、`SMARTCS_XIANYU_ENABLED=true`、服务令牌经 stdin 管道由侧车 `CS_AGENT_TOKEN` 换名注入不落终端）→ Mac 侧车 kill（cookie 单点登录让位）→ `--profile xianyu up -d --build`。**两个首跑缺陷当场修掉**：① `.dockerignore` 整目录排除 `target` 使 `Dockerfile.prebuilt` 的 `COPY target/*.jar` 必失败——改 `target/*` + `!target/smart-cs-agent-0.0.1-SNAPSHOT.jar` 放行单个 JAR；② sync 脚本对不存在的远端 `target/` 传单文件报 `change_dir failed`——补 `ssh mkdir -p`。§3 手册错表名 `orders`→`cs_order`、技能包 8→10 一并勘误。验证：四容器 healthy/running（agent 451M/768M 上限、mysql 462M、qdrant 11M、侧车 27M，系统余 1.9G）；`/actuator/health` UP（db/persistence 均 UP）；11 张表 + ORD-001..004/RET-001 播种、utf8mb4 中文完好；侧车 token→/reg→BRIDGE 在线（卖家 2223022576689），45 分钟窗口零超时零异常。**遗留**：买家真机消息验证需用户小号发消息（无法自主完成）；Web UI 需 SSH 隧道（`ssh -N -L 8080:localhost:8080 myserver`） |
