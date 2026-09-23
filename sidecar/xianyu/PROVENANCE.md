# 侧车来源与改动记录（PROVENANCE）

上游：[shaxiu/XianyuAutoAgent](https://github.com/shaxiu/XianyuAutoAgent)
固定 commit：`540bbc26cf02ee6348d997843942776a9be9460b`（2026-06-10, main）
许可证：**GPL-3.0**（本目录 `LICENSE` 为上游原件，随代码分发，不得移除）。

本项目对上游的使用方式：**只取协议层，砍掉大脑**。上游约 2000 行，本侧车
只保留与闲鱼服务器通信所必需的部分；所有涉及 LLM 决策的逻辑都不存在于
本目录——决策全部在 Java 客服 Agent（`/api/chat/send`）内完成。协议脆弱性
（逆向接口随时可能失效、触发风控）被隔离在独立进程，主项目零依赖。

## 逐文件对照

| 文件 | 来源 | 处理 |
| --- | --- | --- |
| `LICENSE` | 上游原件 | 原样保留（GPL-3.0 合规） |
| `utils/xianyu_utils.py` | 上游原件 | **未改动**（sign / mid / uuid / device_id / cookie 解析 / MessagePack 解码） |
| `XianyuApis.py` | 上游原件 | **2 处改动**（见下） |
| `main.py` | 上游 `main.py` 移植 | 协议骨架保留，大脑替换为转发（改动清单见下） |
| `requirements.txt` | 上游裁剪 | 移除 `openai`（侧车不调 LLM） |
| `.env.example` | 重写 | 侧车自有配置项 |
| `tests/test_offline.py` | 新增 | 离线单元测试（不触网） |

## 删除的上游组件（及去向）

| 上游组件 | 作用 | 处置 |
| --- | --- | --- |
| `XianyuAgent.py` | OpenAI 客户端 + 4 个领域 Agent（classify/price/tech/default）+ 意图路由 | **整体删除**。对应职责由 Java 侧 `WorkflowRouter` + 10 个 Workflow 承担（对照实验已证明差异，见 docs-77） |
| `prompts/*.txt` | 4 套系统提示词 | **删除**。提示词与话术决策都在 Java 侧 |
| `context_manager.py` | SQLite 对话历史 + 商品表 + 议价计数 | **删除**。对话状态由 Java `ConversationStore` 管理；议价 v2 再加（docs-76 §5.2 决策） |
| `_safe_filter`（XianyuAgent.py 内） | 站外联系方式关键词过滤（微信/QQ/支付宝/银行卡/线下） | **随大脑删除**。X4 已在 Java 出站侧以 `OutboundSafetyFilter` 重新实现：从平台裸关键词收窄为"联系方式交换意图"正则（如 `加.{0,4}微信`），因为本项目知识库的排查话术会合法提及"微信不提醒"等平台名词，裸关键词会误杀；对所有渠道生效，不只闲鱼 |
| 阶梯降价/议价策略 | 价格 Agent 的动态 temperature | **删除**（v2 议价 Workflow 时以 Skill Package 形式回到 Java 侧） |

## 保留的上游逻辑（协议层）

WebSocket 连接与注册（`/reg` + token）、心跳维护与超时重连、token 定时刷新
（触发重连）、同步包解密（MessagePack）、聊天/输入中/系统/订单状态消息判型、
消息时效过滤（5 分钟）、商品 ID 解析、`send_msg` 发送帧格式、卖家关键词
人工接管开关、模拟打字延迟、连接 5 秒退避重连。

## main.py 相对上游的具体改动

1. **大脑替换**：`bot.generate_reply(...)` → `compose_reply()`。
   ECHO 模式本地回显（X1 验证协议）；BRIDGE 模式经 `CsAgentClient` 转发
   `POST /api/chat/send`，回复原文发回闲鱼。**商品标题/描述不塞进消息正文**
   （防止污染 Java 侧路由与检索评分），改为会话注册时携带 `itemSnapshot`，
   由 Java 侧生成商品知识文档（docs-76 §5.2 设计点 2）。
2. **会话映射外移**：上游 `context_manager` 按 chat_id 记 SQLite 历史；
   本侧车无状态，`conversationId = xianyu_{buyerId}_{itemId}` 直接由
   `(buyer, item)` 组合生成，Java 侧幂等注册（buyerId → `USER-XY-{hash}`）。
3. **商品信息**：上游 SQLite 持久化商品表 → 进程内 dict 缓存；获取失败不
   阻塞回复（上游失败直接 return 丢单）。
4. **出站节流**（新增，X4 风控缓解）：`pace_reply()` 保证同一买家两次自动
   回复间隔 ≥ `REPLY_MIN_INTERVAL`（默认 3.5s，上限等待 10s）。上游无此机制。
5. **转人工暂停**（新增，X4）：`/api/chat/send` 返回 `handoffRequested=true`
   时把该会话置入人工接管集合（复用卖家句号开关，句号可恢复）；上游的转人工
   只是提示词里的一句话，无协议级暂停。
6. **免责话术**（新增，X4）：每个会话首条自动回复末尾追加一次
   `DISCLAIMER_TEXT`（置空关闭）；上游无此机制。
7. **HTTP 不阻塞事件循环**：BRIDGE 的阻塞 HTTP 调用经 `asyncio.to_thread`
   下沉线程池，避免长超时饿死心跳任务（上游同步 LLM 调用有同样问题，本侧车修正）。
8. **交互式配置改为无人值守**：上游 `check_and_complete_env()` 启动时
   `input()` 交互补配置、风控时 `input()` 等人工粘贴新 Cookie；侧车作为无人
   常驻进程改为启动时校验 + 失败即退出，风控处置指引打到日志。
9. **订单状态消息**：上游仅记录；本侧车同样只记录并显式注明"人工处理，
   v1 不自动接单"（docs-76 §5.2 设计点 5）。
10. **日志默认级别** DEBUG → INFO（常驻进程降噪）。
11. 卖家人工接管开关、心跳/token/重连参数、模拟打字等协议层行为原样保留。

## XianyuApis.py 相对上游的改动

1. 风控分支（`RGV587_ERROR` / "被挤爆啦"）：上游交互式 `input()` 粘贴新 Cookie
   后重试 → 改为打日志说明处置方式后 `sys.exit(1)`，由运行者更新 `.env` 的
   `COOKIES_STR` 后重启。
2. **TLS 指纹伪装（2026-09-09）**：阿里 SM 风控升级为按 TLS 指纹拦截——Python
   `requests` 的握手特征会被 `RGV587_ERROR::SM::STAFF` 直接拒绝（cookie 完全有效
   也一样），真浏览器放行。会话改用 `curl_cffi`（`impersonate="chrome"`，可用
   环境变量 `XIANYU_IMPERSONATE` 覆盖）以 Chrome TLS 指纹发请求；未安装
   `curl_cffi` 时回退 `requests`（本地离线测试不触网，行为不变）。连带适配两处
   CookieJar 差异：`clear_duplicate_cookies` 的去重重设与 `update_env_cookies`
   的字符串化统一走 `_cookie_pairs` 辅助函数——它从底层 stdlib CookieJar 取
   Cookie 对象（curl_cffi 的 `Cookies.jar` 正是它），既兼容两个库的迭代协议，
   也绕开 curl_cffi 同名跨域 cookie 时 `get()` 抛 `CookieConflict` 的问题。
   诊断证据：同一 cookie，requests 被拒、curl_cffi 直接过
   `mtop.taobao.idlemessage.pc.login.token`。

## 已知边界

- 逆向协议无官方支持，随时可能失效；失效只影响本目录进程（可随时关闭）。
- Cookie 有效期有限（通常数天到数周），过期需从浏览器重取。
- 触发风控的概率随自动回复量上升；节流 + 免责话术 + 小号是缓解而非消除。
