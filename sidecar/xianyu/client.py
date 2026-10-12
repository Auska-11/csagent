"""Authenticated Java client; no platform protocol or Agent decisions."""

import threading
from loguru import logger


class ConversationPausedError(Exception):
    """Java 侧会话处于人工模式（409 XIANYU_CONVERSATION_PAUSED），卖家已接管。

    抛给调用方后侧车应静默并把本地状态对齐为人工模式，
    直到卖家句号恢复（经 Java 确认）为止。
    """


class MessagePendingError(Exception):
    """The same event is still being executed on the Java side."""


class MessageResultUnknownError(Exception):
    """The event may have executed; automatic replay is unsafe."""


class CsAgentClient:
    """Java 客服 Agent 的 HTTP 客户端（BRIDGE 模式）。

    会话注册结果按 (buyerId, itemId) 缓存；买家 JWT 过期时自动重注册一次。
    本类的方法是同步阻塞的，调用方须用 asyncio.to_thread 包裹，避免饿死心跳任务。
    """

    def __init__(self, base_url, service_token, timeout=45.0):
        import requests

        self.base_url = base_url.rstrip("/")
        self.service_token = service_token
        self.timeout = timeout
        self._requests = requests
        self._thread_sessions = threading.local()
        self._sessions = {}  # (buyer_id, item_id) -> {"userId","token","conversationId"}
        self._cache_lock = threading.RLock()

    @property
    def session(self):
        if not hasattr(self._thread_sessions, "session"):
            self._thread_sessions.session = self._requests.Session()
        return self._thread_sessions.session

    def _post(self, path, payload, token):
        resp = self.session.post(
            self.base_url + path,
            json=payload,
            headers={"Authorization": f"Bearer {token}"},
            timeout=self.timeout,
        )
        if resp.status_code in (401, 403):
            raise PermissionError(f"{path} 认证失败({resp.status_code})")
        if resp.status_code == 404:
            raise ConnectionError(f"{path} 不存在（X2 会话端点未部署或路径不符）")
        if resp.status_code == 409:
            error_code = ""
            try:
                error_code = resp.json().get("errorCode", "")
            except Exception:
                pass
            if error_code == "XIANYU_CONVERSATION_PAUSED":
                raise ConversationPausedError(f"{path} 会话处于人工模式（卖家接管中）")
            if error_code == "MESSAGE_PROCESSING":
                raise MessagePendingError(error_code)
            if error_code in ("MESSAGE_RESULT_UNKNOWN", "MESSAGE_PAYLOAD_CONFLICT"):
                raise MessageResultUnknownError(error_code)
            raise ConnectionError(f"{path} 冲突({error_code or resp.status_code})")
        resp.raise_for_status()
        return resp.json()

    def _get(self, path, token):
        """GET 服务令牌端点；404 返回 None（调用方按"无此会话"语义处理），其余失败抛。"""
        resp = self.session.get(
            self.base_url + path,
            headers={"Authorization": f"Bearer {token}"},
            timeout=self.timeout,
        )
        if resp.status_code == 404:
            return None
        if resp.status_code in (401, 403):
            raise PermissionError(f"{path} 认证失败({resp.status_code})")
        resp.raise_for_status()
        return resp.json()

    def get_manual_mode(self, buyer_id, item_id):
        """查询 Java 侧人工模式（GET /conversations/manual-mode）。

        返回 True/False；404（会话从未到达 Java）返回 None；网络/服务异常返回
        None 并记 WARN——调用方把 None 视为"查询失败"，保留本地状态不翻转。
        """
        try:
            data = self._get(
                f"/api/xianyu/conversations/manual-mode?buyerId={buyer_id}&itemId={item_id}",
                self.service_token,
            )
        except Exception as exc:
            logger.warning("查询 Java 人工模式失败: exceptionType={}", type(exc).__name__)
            return None
        if data is None:
            return None
        return bool(data.get("manualMode", False))

    def ensure_session(self, buyer_id, item_id, item_snapshot=None):
        key = (buyer_id, item_id)
        with self._cache_lock:
            cached = self._sessions.get(key)
        if cached:
            return cached
        body = {"buyerId": buyer_id, "itemId": item_id}
        if item_snapshot:
            body["itemSnapshot"] = item_snapshot
        data = self._post("/api/xianyu/sessions", body, self.service_token)
        session = {
            "userId": data["userId"],
            "token": data["token"],
            "conversationId": data["conversationId"],
        }
        with self._cache_lock:
            self._sessions[key] = session
        logger.info(f"会话注册成功: {session['userId']} / {session['conversationId']}")
        return session

    def chat(self, buyer_id, item_id, message, item_snapshot=None, message_id=None):
        """注册（如需）→ /api/chat/send → 完整响应 dict（reply + handoffRequested）。

        handoffRequested=true 表示 Java 侧工单创建成功、会话终态 HANDED_OFF，
        侧车据此暂停该会话自动回复（X4 双层转人工，docs-76 §5.2）。
        """
        try:
            session = self.ensure_session(buyer_id, item_id, item_snapshot)
            data = self._post(
                "/api/chat/send",
                {"conversationId": session["conversationId"], "message": message, **({"messageId": message_id} if message_id else {})},
                session["token"],
            )
        except PermissionError:
            # 买家 JWT 过期或服务令牌失效：丢缓存后重注册一次
            with self._cache_lock:
                self._sessions.pop((buyer_id, item_id), None)
            session = self.ensure_session(buyer_id, item_id)
            data = self._post(
                "/api/chat/send",
                {"conversationId": session["conversationId"], "message": message, **({"messageId": message_id} if message_id else {})},
                session["token"],
            )
        reply = data.get("reply")
        if not isinstance(reply, str) or not reply.strip():
            raise ValueError("/api/chat/send did not return a non-empty reply")
        return data

    def set_manual_mode(self, buyer_id, item_id, enabled):
        """同步人工模式到 Java（服务令牌端点）。返回是否成功（bool），失败不抛。"""
        try:
            self._post(
                "/api/xianyu/conversations/manual-mode",
                {"buyerId": buyer_id, "itemId": item_id, "enabled": bool(enabled)},
                self.service_token,
            )
            return True
        except Exception as exc:
            logger.warning("人工模式同步 Java 失败: exceptionType={}", type(exc).__name__)
            return False

    def add_seller_message(self, buyer_id, item_id, text, resume_bot=False, message_id=None):
        """卖家原文同步 Java 历史；携带事件 ID 时由消息队列按同一 ID 有限重试。"""
        try:
            self._post(
                "/api/xianyu/conversations/seller-messages",
                {"buyerId": buyer_id, "itemId": item_id, "text": text,
                 "resumeBot": bool(resume_bot), **({"messageId": message_id} if message_id else {})},
                self.service_token,
            )
            return True
        except Exception as exc:
            logger.warning(f"卖家消息同步 Java 失败: exceptionType={type(exc).__name__}")
            if message_id:
                raise
            return False

    def push_item_facts(self, item_id, price=None, stock=None):
        """热更新推送返回确认结果；失败由 main 保存待同步事实并暂停自动报价。"""
        if not price and stock is None:
            return False
        body = {}
        if price:
            body["price"] = price
        if stock is not None:
            body["stock"] = int(stock)
        try:
            self._post(f"/api/xianyu/items/{item_id}/facts", body, self.service_token)
            return True
        except Exception as exc:
            logger.warning("商品事实推送 Java 失败: exceptionType={}", type(exc).__name__)
            return False
