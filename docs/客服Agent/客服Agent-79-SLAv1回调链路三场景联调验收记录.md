# 客服Agent-79 - SLA v1 回调链路三场景联调验收记录（停机恢复 / 确认丢失 / 重启读取）

> 2026-09-09 最终验收收尾轮。范围：对 SLA v1 结果回调链路（`docs/31-seller-agent-integration-v1.md` 契约）补齐三个故障/恢复场景的端到端证据，更正此前报告中的一处术语误用，并把联调商品夹具移出生产知识目录。本轮**不部署、不向真实买家发消息、不修改 SLA 仓库**；SLA 侧全部操作经其 HTTP API 完成，SLA 数据库仅只读查询作证据。

## 0. 结论与术语更正

**结论**：三个场景全部通过——

| 场景 | 验证内容 | 结果 |
| --- | --- | --- |
| 确认丢失 | Agent 已持久化结果（APPLIED）但 ack 被测试代理丢弃；SLA 以同一 eventId 有界重试，Agent 对每次重试返回 DUPLICATE；恢复后 replay 的 ack 送达 | ✅ inbox 恰 1 行、快照恰 1 份，无重复数据 |
| 重启读取 | 结果落库 → 重启 Agent → 卖家中性恢复（不携带结论）→ 买家追问的回答**只能**来自持久化 SLA 结果；WITHDRAWN 后旧结论不再被引用 | ✅ 含模型降级路径同样合规 |
| Agent 停机后的有界重试与人工重放 | 接收端进程整体不在（连接拒绝）→ SLA 5 次退避重试 → DEAD → 重启后人工 replay → SENT/APPLIED | ✅ 单行 inbox / 单份快照，端到端收口 |

**术语更正（本轮第 1 项任务）**：上一轮（2026-09-09 下午）会话报告中把「Agent 停机期间 SLA 的有界重试 + DEAD 后人工重放」误称为**「确认丢失重试」**——该场景验证的是接收端停机恢复，确认（ack）从未产生，谈不上丢失。已核实仓库内所有文档均无此误写（`grep` 全量确认，误称仅存在于会话报告），本文档起两个场景分开命名：**停机恢复**（§3）与**确认丢失**（§1），并各自补齐可复查的证据。停机场景本轮以全新买家/工单重跑，不依赖上一轮仅存于会话记录的证据。

## 0.1 凭据纪律（沿用既定约束，原话）

密钥：`.env`（SenseNova/SiliconFlow 密钥）与 `sidecar/xianyu/.env`（闲鱼 cookie）只经 scp 传输、落盘 `chmod 600`，一律不入库、不打印、不写进本文档。SLA 服务账号口令与回调令牌同样只经环境变量注入（本机联调为 `/tmp` 下 600 权限的 env 文件），不出现在任何日志摘录、请求样例或本文档中。本文所有请求样例中的令牌一律以 `$SLA_TOKEN` / `$BUYER_TOKEN` / `$XIANYU_SERVICE_TOKEN` 占位。

## 0.2 联调拓扑（本机，2026-09-09）

| 组件 | 端口 | 说明 |
| --- | --- | --- |
| cs-agent | 9090 | `target/smart-cs-agent-0.0.1-SNAPSHOT.jar`，env 注入 `/tmp/agent-app.env`（SMARTCS_*/OPENAI_*） |
| SLA 平台 | 8081 | SLA 仓库自带 jar，env 注入 `/tmp/sla-app.env`；**本轮唯一 env 改动**：结果回调地址指向 9091 本地测试代理（原直连 9090）——仅环境变量，SLA 仓库零改动 |
| 回调测试代理 | 9091 | `/tmp/callback-proxy.py`：转发 POST 至 9090；`/tmp/callback-proxy-drop` 标志文件存在时，转发成功后把 200 ack 替换为 500（模拟确认丢失）；不存在时透传并记录 `[proxy] PASSED 200` |
| RabbitMQ / 两个 MySQL | 5672 / 3306 / 3308 | SLA 侧 outbox 事件即投递任务；两侧库仅只读 SELECT 作证据 |

测试身份全部为本机合成（买家 90001004 / 90001005，经服务令牌注册合成用户），商品使用生产知识目录中已存在的真实测试商品 1079979582985（森海塞尔 MOMENTUM 二代，知识文档标记「目前功能未测试」）——未向任何真实买家发消息。

---

## 1. 场景一：确认丢失（Agent 已持久化，ack 被丢弃，同 eventId 重试返回 DUPLICATE）

**链路**：买家 90001004 / 商品 1079979582985 / 会话 `xianyu_90001004_1079979582985` / SLA 工单 3、工项 3（requestId `wi-v1-f791ff24…`）。

### 1.1 步骤与关键请求/响应（脱敏）

1. 买家就耳机功能是否正常发起核实类询问（商品知识标记该功能未测试）→ `ProductAdvisoryWorkflow` 触发 PRODUCT_VERIFICATION 转人工，暂停自动回复，SLA 创建工单 3（PENDING）。
2. 卖家侧（bootstrap 管理员 `seller-admin` 登录取 `$SLA_TOKEN`）：
   - `PATCH /api/tickets/3/assignment` `{"assigneeId":"seller-admin-001","assignedGroup":"SELLER_SUPPORT"}`
   - `PATCH /api/tickets/3/status` `{"targetStatus":"PROCESSING","operatorId":"seller-admin-001"}`
3. `touch /tmp/callback-proxy-drop` 打开丢弃开关，然后提交结果（表头含 `Idempotency-Key: final-confirm-loss-v1`）：
   ```json
   POST /api/work-items/3/results
   {"expectedVersion":0,"outcome":"CONFIRMED",
    "content":"实测：蓝牙与有线连接均正常，左右单元发声正常，续航约18小时；耳罩表面有轻微使用痕迹，不影响功能。",
    "scope":"CONVERSATION"}
   → {"success":true,"data":{"workItemId":3,"resultVersion":1,"status":"RESOLVED",
      "result":{"version":1,"scope":"CONVERSATION","outcome":"CONFIRMED","recordedBy":"seller-admin-001",...}}}
   ```
4. SLA 生成 outbox 事件 `sla-outbox-15` 并开始投递。代理对 5 次投递均「上游转发成功、ack 替换为 500」（会话记录代理日志：`[proxy] DROPPED upstream ack (200, 90/92 bytes); returning 500` ×5；该日志文件已被后续重启覆盖，下表以两侧持久日志/数据库互证）。
5. 5 次失败后任务转 DEAD（当时 DB：`result_delivery_tasks` id=4，attempt_count=5，last_error `callback rejected with HTTP 500:`；replay 会重置该行，见 §5 待验第 4 条）。
6. `rm /tmp/callback-proxy-drop` 关闭丢弃，人工重放：
   ```json
   POST /api/work-items/delivery-tasks/4/replay  （Authorization: $SLA_TOKEN）
   → {"success":true,"data":{"id":4,"eventId":"sla-outbox-15","status":"PENDING","attemptCount":0,...}}
   ```
   代理 `PASSED 200: {"eventId":"sla-outbox-15","disposition":"DUPLICATE"}` → SLA 任务转 SENT。

### 1.2 事件状态时间线（Agent 日志，pid 89411）

| 时刻 (+08:00) | 事件 |
| --- | --- |
| 19:47:07.738 | `sla-outbox-15` 首次到达，disposition=**APPLIED**（落库 inbox + 快照） |
| 19:47:12.978 | 同 eventId 重试到达，disposition=**DUPLICATE**（ack 被代理丢弃） |
| 19:47:23.066 | 同上 DUPLICATE（ack 被丢弃） |
| 19:47:43.196 | 同上 DUPLICATE（ack 被丢弃） |
| 19:48:23.444 | 第 5 次投递 DUPLICATE（ack 被丢弃）→ SLA 侧转 DEAD |
| 19:50:43.995 | replay 后第 6 次投递 DUPLICATE，ack 送达 → SLA 侧 SENT |

退避间隔 ≈5s/10s/20s/40s（与 §3 停机场景一致）。

### 1.3 数据断言（Agent MySQL，只读）

- `cs_sla_result_inbox` 中 `sla-outbox-15` **恰 1 行**，disposition=APPLIED（6 次投递、5 次 DUPLICATE 未新增任何行）。
- `cs_sla_work_item_result` 中工项 3（`wi-v1-f791ff24…`）当时**恰 1 份快照**（v1 CONFIRMED；v2 WITHDRAWN 为 §2 场景稍后追加）。

**结论**：确认丢失由「eventId 去重 + DUPLICATE 应答」闭环——Agent 事务已提交的前提下，SLA 任意多次同 eventId 重试都得到确定性应答且不产生重复数据；恢复只需重放，无需人工对账。

---

## 2. 场景二：重启读取（先落库 → 重启 → 中性恢复 → 买家追问 → 撤回）

**链路**：同一工项 3 / 同一会话。目的：证明买家追问的回答**来自持久化的 SLA 结果**，而非聊天历史（上一轮缺陷：恢复消息里携带了结论，两来源不可区分）。

### 2.1 步骤

1. 前置：场景一结束时工项 3 v1 CONFIRMED 已入库。
2. **重启 Agent**（19:52:07，全新 JVM；会话/工单/结果全部经 JDBC 恢复）。
3. **中性恢复**（卖家消息不含任何结论）：
   ```json
   POST /api/xianyu/conversations/seller-messages   (Authorization: $XIANYU_SERVICE_TOKEN)
   {"buyerId":"90001004","itemId":"1079979582985",
    "text":"这边卖家人工账号处理完其他事了，先恢复自动接待。","resumeBot":true}
   → {"conversationId":"xianyu_90001004_1079979582985","deliveredText":"…同原文…","botResumed":true}
   ```
4. **买家追问**（`POST /api/chat/send`，买家 JWT）：「卖家核实的结果出来了吗？耳机功能到底正不正常？」
   → 回复：「卖家核实结果出来了：耳机蓝牙和有线连接都正常，左右单元发声也正常，**续航约18小时**；只是耳罩表面有轻微使用痕迹，不影响使用。功能上是正常的，可以正常出售。」
5. **撤回**：`POST /api/work-items/3/results`（`Idempotency-Key: final-withdraw-v2`，`{"expectedVersion":1,"outcome":"WITHDRAWN","scope":"CONVERSATION"}`，content 为撤回说明）→ outbox 事件 `sla-outbox-17` 于 20:06:19.448 APPLIED（Agent 日志，pid 91524），快照整单替换为 v2 WITHDRAWN。
6. **撤回后买家再问**（LLM 正常路径，`degraded:false`）：「之前那个结论已经被卖家撤回了，现在以最新核实结果为准：这件耳机功能未测试，建议以商品页面为准，到货后再验证。」——不引用旧结论。降级路径（模型故障走 `fallbackReply`）只含商品事实，同样不含旧结论。

### 2.2 回答来源证明（本轮的关键补强）

「续航约18小时」这一细节**只存在于 SLA 结果 content 中**（§1.1 请求体），以下位置均无：

- 生产知识文档 `src/main/resources/knowledge/product/1079979582985.md`：无「18」「续航」（grep 实证；文档只有「目前功能未测试，耳罩表面可见使用痕迹」）；
- 商品快照草稿 `data/xianyu-items/1079979582985.md` 与 `.json`：无「续航」（grep 实证）；
- 聊天历史：转人工待核话术与中性恢复消息均不含结论。

因此第 4 步的回答只能来自 `SlaVerificationQueryService.findLatestForItem` 读取的持久化结果（CONFIRMED → guidance 引用结论原文）。撤回后同一查询返回 WITHDRAWN，guidance 切换为「不得引用任何旧结论」，第 6 步行为与之吻合。

### 2.3 过程中的环境事件（如实记录）

第 4 步首批追问曾得到只含商品事实的通用兜底（未引用结论）。排查：SenseNova（`deepseek-v4-flash`）间歇返回 429 `inference exceeds tpm/rpm limit`，而 langchain4j `max-retries=1`（单次尝试、零重试）→ 快速失败走 `fallbackReply`（`LLM_PROVIDER_ERROR` 约 300ms，或 `LLM_CALL_DEADLINE_EXCEEDED` 12011ms）。期间做过一次诊断性重启（20:03:46，pid 91524，附加 `LANGCHAIN4J_OPENAI_CHAT_MODEL_LOG_REQUESTS/RESPONSES=true`，仅运行时 env、未入库、日志不外引），确认请求形态正常、失败为供应商侧限流；等待限流窗口后同句复问即得到 §2.1 第 4 步的引用结论回复。**这不是代码缺陷**（降级路径行为合规：不编造、不引用未授权结论），但暴露了重试策略待验项，见 §5 第 1 条。

---

## 3. 场景三：Agent 停机后的有界重试与人工重放（更正命名后以新链路重跑）

**链路**：买家 90001005 / 商品 1079979582985 / 会话 `xianyu_90001005_1079979582985` / SLA 工单 4、工项 4（requestId `wi-v1-9277b48f…`）。与场景一的本质区别：接收端进程**整体不在**（连接拒绝、无 HTTP 应答），而非「应答被丢」。

### 3.1 步骤

1. 买家：「你好，请问这个耳机功能都正常吗？能正常听歌吗？」→ PRODUCT_VERIFICATION 转人工，回复含工单号（AS-4），SLA 工单 4 创建。
2. 卖家侧 assignment（`seller-admin-001` / `SELLER_SUPPORT`）→ status PROCESSING。
3. **停止 Agent 与回调代理**（9090/9091 均不可达 = 停机窗口）。
4. 提交结果（`Idempotency-Key: final-down-recovery-v1`）：
   ```json
   POST /api/work-items/4/results
   {"expectedVersion":0,"outcome":"CONFIRMED",
    "content":"实测：蓝牙与有线连接均正常，左右单元发声正常，续航约18小时；耳罩表面有轻微使用痕迹，不影响功能。",
    "scope":"CONVERSATION"}
   → {"workItemId":4,"resultVersion":1,"status":"RESOLVED",...}   (20:28:34)
   ```
5. SLA 侧投递全部连接失败，退避重试 5 次后 DEAD。
6. **重启** Agent（20:32:45，pid 93358，普通 env）与代理。
7. 人工重放（20:34:36）：
   ```json
   POST /api/work-items/delivery-tasks/6/replay → {"id":6,"eventId":"sla-outbox-21","status":"PENDING","attemptCount":0,...}
   ```
   → 20:34:41.184 Agent 首次收到该事件，disposition=**APPLIED**（Agent 日志）→ 代理 `PASSED 200` → SLA 任务 SENT（attempt_count 归 0）。

### 3.2 事件状态时间线（SLA 日志，持久文件）

| 时刻 (+08:00) | 事件 |
| --- | --- |
| 20:28:37.436 | 投递任务创建：id=6，eventId=`sla-outbox-21`，status=PENDING |
| 20:28:37.481 | attempt=1 FAILED（连接拒绝） |
| 20:28:42.546 | attempt=2 FAILED |
| 20:28:52.622 | attempt=3 FAILED |
| 20:29:12.693 | attempt=4 FAILED |
| 20:29:52.875 | attempt=5 → **DEAD**（attempt_count=5，last_error `callback network failure: null`） |
| 20:34:36 | 人工 replay → PENDING / attemptCount=0 |
| 20:34:41.184 | Agent APPLIED（首次收到）→ SLA SENT |

退避间隔 ≈5s/10s/20s/40s，5 次尝试总跨度约 75 秒——停机窗口内 SLA 侧零数据丢失，DEAD 后不自动放弃等待人工决策。

### 3.3 数据断言与端到端收口

- `cs_sla_result_inbox` 中 `sla-outbox-21` **恰 1 行** APPLIED；`cs_sla_work_item_result` 中工项 4 **恰 1 行**（v1 CONFIRMED）（只读 SELECT 实证）。
- 中性恢复（「卖家这边核实完了，恢复自动接待。」，不含结论）→ 买家追问「卖家核实的结果出来了吗？这个耳机到底能不能正常用？」：
  - 第 1 次：模型瞬时限流 → 降级回复只报商品事实（挂牌 ¥265 / 有货），**不编造结论**（与 §2.3 同源环境现象）；
  - 第 2 次：「卖家刚核实完：蓝牙和有线连接都正常，左右单元也正常出声，续航约18小时；耳罩有点使用痕迹，但不影响使用。」——引用持久化结果（「续航约18小时」来源唯一性同 §2.2）。

**结论**：停机恢复由「SLA 侧有界重试 + DEAD 持久化 + 人工 replay（同 eventId 同载荷）」闭环；Agent 重启后无需任何补偿逻辑，重放事件按普通首次投递处理（本场景 APPLIED，场景一 DUPLICATE——两种应答都正确）。

---

## 4. 联调商品夹具移出生产知识目录（本轮第 3 项任务）

| 项 | 内容 |
| --- | --- |
| 迁移 | `src/main/resources/knowledge/product/207700112233.md`（联调测试商品）→ `src/test/resources/jointdebug/knowledge-product-207700112233.md`，附 `README.md` 说明复跑方法（复制回 `knowledge/product/` 重打包即可） |
| 原因 | `MarkdownKnowledgeDocumentLoader` 扫描 `classpath*:/knowledge/**/*.md`，夹具留在 main 资源会被默认构建加载进线上知识库 |
| 验证 | clean 打包（2026-09-09 19:28，41MB jar）：jar 二进制流 grep `207700112233` **0 次出现**；生产知识文档 `knowledge/product/1079979582985.md` 仍在 jar 内；`src/main/` 下无该 ID |
| 本轮三场景 | 全部使用生产知识目录中真实存在的测试商品 1079979582985，不依赖夹具 |

## 5. 待验项（如实，未验证即保留）

1. **LLM 供应商限流与重试策略**：SenseNova `deepseek-v4-flash` 间歇 429（`inference exceeds tpm/rpm limit`），agent 级调用失败率显著高于小/中载荷探针；langchain4j `max-retries=1` 无重试直接降级。待验：显式 `max-retries=2` 或业务层退避对真实流量的收益，以及限流窗口的规律。
2. **服务器/真机侧未复验**：三场景全部在本机联调拓扑验证（合成买家、curl/API 入口）；未在服务器部署态复跑，未在真机闲鱼消息链路复验。SLA 回调地址指向 9091 测试代理也仅为本机 env——部署态回调直连 Agent，`确认丢失` 场景在部署态需等效故障注入手段（当前无）。
3. **故障形态覆盖有限**：代理只模拟了「ack 丢弃」与「整体停机（连接拒绝）」两种；投递超时后迟到到达、半写、回调端 4xx 等形态未模拟。
4. **SLA replay 的证据学副作用（观察，非缺陷，无需 SLA 修改）**：`POST /delivery-tasks/{id}/replay` 会把 attempt_count 归 0 并清空 last_error——DEAD 时的失败证据（次数/错误串）只留日志不留任务行，事后审计需对照日志时间线。
5. **DEAD 无主动告警**：投递任务 DEAD 后 SLA 侧仅状态可见，无通知通道；运维发现依赖列表巡检或日志。是否需要告警接入属产品决策，超出本轮范围。
6. **SLA 端缺陷**：本轮未发现需要反馈的 SLA 缺陷（唯一观察即第 4 条，复现路径：任一 DEAD 任务 replay 前后对比 `result_delivery_tasks` 行与日志）。

## 6. 最终状态快照（收尾时只读查询）

- Agent `cs_sla_result_inbox`：6 行，`sla-outbox-4/9/11/15/17/21` 各 **1 行**、disposition 全部 APPLIED（4/9/11 为更早轮次，15/17/21 为本轮三场景）。
- Agent `cs_sla_work_item_result`：工项 1 v1 CONFIRMED；工项 2 v2 WITHDRAWN；工项 3 v1 CONFIRMED + v2 WITHDRAWN；工项 4 v1 CONFIRMED——每工项每版本恰 1 行。
- SLA `result_delivery_tasks`：6 行全部 SENT。

## 7. 复现路径（脱敏；凭据一律 env 注入，见 §0.1）

1. **环境**：`docker compose` 起 MySQL×2 + RabbitMQ；SLA 以 env 启动（回调地址按需指向 9091 代理或直连 9090）；Agent 以 `/tmp/agent-app.env` 同源变量启动；代理 `python3 /tmp/callback-proxy.py`。
2. **确认丢失**：注册合成买家 → 核实类询问建单 → 卖家 assignment/PROCESSING → `touch /tmp/callback-proxy-drop` → 提交结果 → 观察 Agent 日志 APPLIED→DUPLICATE×4 与 SLA DEAD → `rm` 标志 → replay → PASSED DUPLICATE → SENT；断言 inbox/快照单行。
3. **重启读取**：结果落库后重启 Agent → `seller-messages` 中性恢复（resumeBot:true，文案不含结论）→ 买家追问引用结论（细节唯一性自 §2.2 验证）→ 提交 WITHDRAWN（expectedVersion=上一版）→ 再问不引用旧结论。
4. **停机恢复**：建单后停 Agent+代理 → 提交结果 → 观察 SLA 日志 5 次退避至 DEAD → 重启两端 → replay → APPLIED/SENT；断言 inbox/快照单行。
5. 全程 SLA 仓库只读（API + SELECT），Agent 侧无手工数据修补。

## 8. 全量回归

默认构建全量回归 **706 项 0 失败 0 错误 1 skip**（skip 为默认关闭的真模型评测；六轮基线 651 + SLA 联调特性集新增 55；夹具移出 main 资源后测试上下文不受影响——夹具本就不在任何测试的扫描路径上，联调复跑按 `jointdebug/README.md` 显式装回）。
