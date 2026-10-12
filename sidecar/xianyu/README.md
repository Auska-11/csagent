# 闲鱼侧车（Xianyu Sidecar）

独立 Python 进程，承接闲鱼登录、WebSocket 消息、心跳与重连。BRIDGE 模式将买家消息交给 Java 客服 Agent，业务判断和话术生成由 Java 完成。

协议来源、改动与许可证保留在 [PROVENANCE.md](PROVENANCE.md) 和 [LICENSE](LICENSE)。

## 运行

```bash
cd sidecar/xianyu
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
python main.py
```

`.env` 填入有效的 `COOKIES_STR`。ECHO 模式会回显买家消息，仍会连接真实平台；离线测试无需 Cookie，也不加载用户的 BRIDGE 配置。

| 配置 | BRIDGE 用途 |
| --- | --- |
| `SIDECAR_MODE=BRIDGE` | 启用 Java 业务处理 |
| `CS_AGENT_URL` | Java 地址；本地或 SSH 隧道均可 |
| `CS_AGENT_TOKEN` | 与 Java 私有 `SMARTCS_XIANYU_SERVICE_TOKEN` 一致，至少 32 字节 |
| `SIDECAR_STATE_FILE` | 买家映射、暂停状态与待同步事实；默认 `data/sidecar-state.json` |
| `SIDECAR_JOURNAL_FILE` | SQLite 收件箱；默认与状态文件同目录的 `message-journal.sqlite3` |
| `SIDECAR_QUEUE_CAPACITY=256` | 在途消息容量，预留 16 条给卖家控制 |
| `SIDECAR_WORKERS=4` | 固定买家任务数量，另设一个卖家控制任务 |
| `CS_AGENT_TIMEOUT=45` | Java HTTP 超时，覆盖默认 30 秒整轮预算 |

Java 需配置私有 JWT 密钥、`SMARTCS_XIANYU_ENABLED=true` 和服务令牌。真实接入使用 JDBC 持久化，挂载 Java 商品资料目录和侧车数据目录；Java 内存回执不能跨重启保留。

## 单一消息处理路径

- `protocol.py`：逐条解码同步包，生成不可变消息事件。事件键基于解密后的业务消息、卖家身份和平台时间戳；外层包 `mid` 仅用于协议应答，买家相同正文不被合并。
- `delivery.py`：SQLite 入站记录、有界容量、固定工作任务、会话顺序及发送状态。整包有效消息入库后才应答；容量满不应答为成功，等待重新连接和投递。
- `client.py`：线程独立的 HTTP Session、买家 JWT 缓存和认证刷新；重试保持同一 `messageId`。
- `main.py`：平台连接、商品事实同步、人工模式协调与出站发送。接收循环不等待模型或节流，发送前重新检查卖家接管和新控制事件。

入站时限默认 5 分钟；超过时限的自动回复取消。同一会话买家消息按顺序执行，不同会话有限并行；卖家指令可及时阻断等待中的自动回复。

## Java 契约

1. `POST /api/xianyu/sessions` 使用侧车服务令牌，传入平台数字 `buyerId`、`itemId` 和可选 `itemSnapshot`，返回合成买家 JWT 及 `xianyu_{buyerId}_{itemId}` 会话 ID。
2. `POST /api/chat/send` 使用买家 JWT，传入 `conversationId`、`message`、稳定 `messageId`。Java 按租户、身份、会话与消息 ID 保存结果，重复请求直接回放。
3. 平台快照只创建 `profiles/{itemId}.json` 中的待审核资料。卖家在 `/#/admin/xianyu` 保存草稿并审核发布；无需移动 Markdown 或重启。`itemKnowledge=created|existing|skipped|none` 字段名保留兼容接入契约。
4. 平台现势价格和库存通过 `/api/xianyu/items/{itemId}/facts` 独立同步；已读到但 Java 未确认的改价、售出事实会拦截自动报价并保留待同步记录。

平台事实默认缓存 5 分钟，属于最近一次同步结果，并非平台订单状态实时订阅。接口提供热更新，业务确认时重新检查 Java 已确认事实。

## 人工接管

卖家在闲鱼 App 发送完整指令 `。`（可配置）切换状态：暂停先在侧车生效，恢复等待 Java 确认。重复投递的句号不会切换两次。BRIDGE 不因超时自动恢复。

Java `handoffRequested=true` 同样暂停会话，允许本轮转接提示发出；之后等待卖家处理。卖家人工回复携带独立事件 ID 写入 Java 历史，保持暂停；恢复后机器人能读取卖家口径。工作台也可查询、暂停或恢复已建立的买家/商品会话。

## 重启与结果未知

- `PROCESSING` 在重启后恢复入站处理，继续使用原事件 ID 查询 Java 回执；不会因 HTTP 超时自动重新执行已完成业务。
- Java 返回 `MESSAGE_PROCESSING` 时有限退避；`MESSAGE_RESULT_UNKNOWN` 或内容冲突停止自动处理。
- Java 已完成回复保存在 `READY`，恢复发送前仍检查人工接管与消息时效。
- 在 WebSocket 写入前记 `SENDING`；写入异常或此阶段重启记 `UNKNOWN`，不盲目补发。
- `SENT` 仅表示已写入本地连接或已完成控制事件，不能作为平台或买家已收到的证明。没有平台级投递确认，不能宣称端到端 exactly-once。

排查未知状态可在本机只读查询：

```bash
sqlite3 data/message-journal.sqlite3 \
  "SELECT event_id, kind, status, attempts, error FROM messages WHERE status='UNKNOWN';"
```

卖家先在闲鱼客户端核对实际消息；需要补充回复时人工发送。不要删除回执或把 UNKNOWN 直接改回 RECEIVED 来强行重放。未知记录保留供核查，SENT/CANCELLED 在后续入站时清理一天前记录。

## 离线回归

```bash
.venv/bin/python -m unittest discover -s tests -p 'test_*.py'
```

覆盖批量解码、重投去重、容量限制、会话顺序、卖家接管打断、重启恢复、发送结果未知与事实同步失败。测试只使用临时目录和本地模拟服务。
