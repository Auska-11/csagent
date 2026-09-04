# 闲鱼侧车（Xianyu Sidecar）

把闲鱼真实买家消息接入 Java 客服 Agent 的独立 Python 进程。**无脑转发器**：
只负责协议层（cookie 登录、WebSocket 收发、心跳、token 刷新），不做任何
LLM 决策——回复全部来自主项目 `/api/chat/send`。

> 来源与逐条改动记录见 [PROVENANCE.md](PROVENANCE.md)（基于
> XianyuAutoAgent@`540bbc2` 裁剪，GPL-3.0）。设计动机见
> `docs/客服Agent/客服Agent-76-...md` §5。

## 运行

```bash
cd sidecar/xianyu
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env        # 填入 COOKIES_STR（获取方式见 .env.example 注释）
python main.py
```

| 模式 | SIDECAR_MODE | 依赖 | 用途 |
| --- | --- | --- | --- |
| ECHO | `ECHO` | 仅 cookie | X1：验证协议链路，买家消息原样回显 |
| BRIDGE | `BRIDGE` | cookie + Java 服务（X2 端点） | X3+：真消息 → Java Agent → 回复回闲鱼 |

## 转发契约（BRIDGE 模式，Java 侧 X2 已实现）

```
① POST /api/xianyu/sessions        Authorization: Bearer <侧车服务令牌>
   {"buyerId": "...", "itemId": "...", "itemSnapshot": "..."(仅首次)}
→ {"userId": "USER-XY-xxxx", "token": "<买家JWT>", "conversationId": "xianyu_{buyerId}_{itemId}",
   "expiresAt": ..., "tenantId": "xianyu", "itemKnowledge": "created|existing|none"}
   幂等：同 buyerId 恒返回同一合成用户；首次收到 itemSnapshot 时生成商品知识草稿
   （data/xianyu-items/{itemId}.md，人工审校后移入 resources/knowledge/product/ 并重启生效）

② POST /api/chat/send              Authorization: Bearer <买家JWT>
   {"conversationId": "...", "message": "..."}
→ {"reply": "...", "handoffRequested": true|false}
   与现有聊天端点同构；身份只信 JWT，conversationId 原样透传。
   handoffRequested=true 表示 Java 侧工单创建成功、会话终态 HANDED_OFF，
   侧车据此暂停该会话自动回复（见下节）
```

Java 侧开关（`application.yml` 的 `smartcs.xianyu.*`）：`SMARTCS_XIANYU_ENABLED=true` 且
`SMARTCS_XIANYU_SERVICE_TOKEN`（≥32 字节，与本侧车 `CS_AGENT_TOKEN` 一致）；未启用时端点为 404。
买家 JWT 过期后侧车凭 401 自动重注册，`expiresAt` 仅供参考。

## 人工接管（双层）

- **协议层（本侧车）**：卖家本人在闲鱼 App 的会话里发 `。`（`TOGGLE_KEYWORDS`）
  → 该会话暂停/恢复自动回复，消息不进 Java。
- **业务层（Java，X4 已实现）**：HANDOFF Workflow → 工单转人工。`/api/chat/send`
  返回 `handoffRequested: true` 时，侧车把该会话置入人工接管（复用同一开关），
  之后的消息不再自动回复；卖家发 `。` 恢复。两层共用一个暂停集合，
  句号对两种来源的暂停都有效。

## 免责话术与出站加固（X4）

- 每个会话的**首条**自动回复末尾追加一次 `DISCLAIMER_TEXT`
  （默认"（本条由智能助手自动回复，如需人工服务请直接说明）"，置空关闭）。
- 站外联系方式过滤在 **Java 出站侧**实现（`OutboundSafetyFilter`，对所有渠道
  生效，不只闲鱼）：最终回复命中"加微信/支付宝转账/线下面交"等联系方式交换
  意图时整条替换为安全提示，trace 只记命中模式不记原文。

## 安全边界（v1）

不自动接单/下单；订单状态消息只记录日志；Java 侧对 xianyu 租户关闭副作用
能力（`return.apply` 屏蔽，`handoff.request` 放行）；站外联系方式过滤见上节。

## 离线测试

```bash
python tests/test_offline.py   # 不触网、不需要 cookie
```
