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
→ {"reply": "..."}
   与现有聊天端点同构；身份只信 JWT，conversationId 原样透传
```

Java 侧开关（`application.yml` 的 `smartcs.xianyu.*`）：`SMARTCS_XIANYU_ENABLED=true` 且
`SMARTCS_XIANYU_SERVICE_TOKEN`（≥32 字节，与本侧车 `CS_AGENT_TOKEN` 一致）；未启用时端点为 404。
买家 JWT 过期后侧车凭 401 自动重注册，`expiresAt` 仅供参考。

## 人工接管（双层）

- **协议层（本侧车）**：卖家本人在闲鱼 App 的会话里发 `。`（`TOGGLE_KEYWORDS`）
  → 该会话暂停/恢复自动回复，消息不进 Java。
- **业务层（Java，X4）**：HANDOFF Workflow → 工单转人工，Agent 停止自动回复。

## 安全边界（v1）

不自动接单/下单；订单状态消息只记录日志；Java 侧对 xianyu 租户关闭副作用
能力（只读+问答）；站外联系方式过滤在 X4 于 Java 出站侧实现。

## 离线测试

```bash
python tests/test_offline.py   # 不触网、不需要 cookie
```
