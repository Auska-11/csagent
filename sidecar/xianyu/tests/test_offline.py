"""
侧车离线测试：不触网、不需要 cookie、不依赖 Java 服务。

运行：python tests/test_offline.py
"""

import asyncio
import base64
import json
import itertools
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from main import CsAgentClient, ConversationPausedError, XianyuLive  # noqa: E402
from protocol import MessageEvent
from utils.xianyu_utils import trans_cookies  # noqa: E402

DUMMY_COOKIES = "unb=12345; cookie2=xyz; _m_h5_tk=abc_def"


class MockHandler(BaseHTTPRequestHandler):
    """模拟 X2 会话端点 + /api/chat/send。行为由测试注入 server.state 控制。"""

    def log_message(self, *args):
        pass

    def _json(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        payload = json.loads(self.rfile.read(length) or b"{}")
        state = self.server.state
        auth = self.headers.get("Authorization", "")
        state["calls"].append({"path": self.path, "auth": auth, "body": payload})

        if self.path == "/api/xianyu/sessions":
            if auth != f"Bearer {state['service_token']}":
                self._json(401, {"error": "bad service token"})
                return
            state["registered"] += 1
            self._json(200, {
                "userId": f"USER-XY-{payload['buyerId'][-4:]}",
                "token": f"jwt-{state['registered']}",
                "conversationId": f"xianyu_{payload['buyerId']}_{payload['itemId']}",
            })
        elif self.path == "/api/chat/send":
            if state.get("expire_token") and auth == f"Bearer {state['expire_token']}":
                self._json(401, {"error": "expired"})
                return
            if state.get("paused_conversation") == payload.get("conversationId"):
                self._json(409, {
                    "errorCode": "XIANYU_CONVERSATION_PAUSED",
                    "message": "Conversation is in manual mode, seller has taken over",
                })
                return
            self._json(200, {
                "reply": f"ok:{payload['message']}",
                "handoffRequested": bool(state.get("handoff_flag", False)),
            })
        elif self.path == "/api/xianyu/conversations/manual-mode":
            if auth != f"Bearer {state['service_token']}":
                self._json(401, {"error": "bad service token"})
                return
            state["manual_mode"] = payload["enabled"]
            self._json(200, {
                "conversationId": f"xianyu_{payload['buyerId']}_{payload['itemId']}",
                "manualMode": payload["enabled"],
            })
        elif self.path == "/api/xianyu/conversations/seller-messages":
            if auth != f"Bearer {state['service_token']}":
                self._json(401, {"error": "bad service token"})
                return
            state["seller_messages"].append(payload)
            self._json(200, {
                "conversationId": f"xianyu_{payload['buyerId']}_{payload['itemId']}",
                "deliveredText": payload["text"],
                "botResumed": payload.get("resumeBot", False),
            })
        elif self.path.startswith("/api/xianyu/items/") and self.path.endswith("/facts"):
            if auth != f"Bearer {state['service_token']}":
                self._json(401, {"error": "bad service token"})
                return
            state["facts"].append({"path": self.path, "body": payload})
            self._json(200, {"itemId": self.path.split("/")[4], **payload})
        else:
            self._json(404, {"error": "not found"})

    def do_GET(self):
        """GET /api/xianyu/conversations/manual-mode：侧车重启后的状态对齐查询。"""
        parsed = urlparse(self.path)
        state = self.server.state
        auth = self.headers.get("Authorization", "")
        if parsed.path == "/api/xianyu/conversations/manual-mode":
            if auth != f"Bearer {state['service_token']}":
                self._json(401, {"error": "bad service token"})
                return
            query = parse_qs(parsed.query)
            buyer_id = query.get("buyerId", [""])[0]
            item_id = query.get("itemId", [""])[0]
            state["manual_mode_queries"].append({"buyerId": buyer_id, "itemId": item_id})
            # 测试注入：{buyerId}|{itemId} -> True/False；"missing" -> 404
            key = f"{buyer_id}|{item_id}"
            table = state.get("get_manual_mode", {})
            if key in table and table[key] == "missing":
                self._json(404, {"errorCode": "NOT_FOUND", "message": "Xianyu conversation not found"})
                return
            self._json(200, {
                "conversationId": f"xianyu_{buyer_id}_{item_id}",
                "manualMode": bool(table.get(key, False)),
            })
            return
        self._json(404, {"error": "not found"})


def start_mock_server():
    server = ThreadingHTTPServer(("127.0.0.1", 0), MockHandler)
    server.state = {
        "calls": [],
        "service_token": "svc-token",
        "registered": 0,
        "expire_token": None,
        "handoff_flag": False,
        "paused_conversation": None,
        "manual_mode": False,
        "manual_mode_queries": [],
        "get_manual_mode": {},
        "seller_messages": [],
        "facts": [],
    }
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


class MockWebSocket:
    def __init__(self):
        self.frames = []

    async def send(self, raw):
        self.frames.append(json.loads(raw))

    def message_sends(self):
        return [f for f in self.frames if f.get("lwp") == "/r/MessageSend/sendByReceiverScope"]


_live_instances = []
_seller_sequence = itertools.count()


async def process_seller_event(live, chat_id, item_id, text):
    """走生产收消息与控制队列，避免测试保留另一套卖家处理路径。"""
    message = {"1": {"2": chat_id + "@goofish", "5": int(time.time() * 1000),
        "fixtureSequence": next(_seller_sequence), "10": {
            "reminderContent": text, "senderUserId": live.myid,
            "reminderUrl": f"https://www.goofish.com/item?itemId={item_id}"}}}
    packet = {"headers": {"mid": "offline-control"}, "body": {"syncPushPackage": {"data": [
        {"data": base64.b64encode(json.dumps(message).encode()).decode()}]}}}
    await live.handle_message(packet, MockWebSocket())
    row = live.journal.claim(-1)
    if row is None:
        raise AssertionError("Seller event was not admitted")
    event = MessageEvent(**json.loads(row["payload"]))
    result = await live._process_event(event)
    live.journal.set_status(event.event_id, "SENT")
    return result


class OfflineTestCase(unittest.TestCase):
    def run(self, result=None):
        try:
            return super().run(result)
        finally:
            while _live_instances:
                _live_instances.pop().journal.close()


def make_live():
    # 不继承用户本地 BRIDGE 配置，离线测试只连接下面的本地 mock 服务。
    with patch.dict("os.environ", {"SIDECAR_MODE": "ECHO"}):
        live = XianyuLive(DUMMY_COOKIES)
        _live_instances.append(live)
        return live


class TestUpstreamUtils(OfflineTestCase):
    def test_trans_cookies(self):
        cookies = trans_cookies(DUMMY_COOKIES)
        self.assertEqual(cookies["unb"], "12345")
        self.assertEqual(cookies["cookie2"], "xyz")


class TestEchoMode(OfflineTestCase):
    def test_compose_reply_echo(self):
        live = make_live()
        reply, handoff = asyncio.run(live.compose_reply("66001", "90111", "蓝牙耳机还在吗"))
        self.assertEqual(reply, "[echo:xianyu_66001_90111] 蓝牙耳机还在吗")
        # ECHO 模式不接 Java，永不触发转人工暂停
        self.assertFalse(handoff)

    def test_send_msg_frame(self):
        live = make_live()
        ws = MockWebSocket()
        asyncio.run(live.send_msg(ws, "70001", "66001", "你好"))
        sends = ws.message_sends()
        self.assertEqual(len(sends), 1)
        frame = sends[0]
        self.assertEqual(frame["body"][0]["cid"], "70001@goofish")
        receivers = frame["body"][1]["actualReceivers"]
        self.assertIn("66001@goofish", receivers)
        self.assertIn(f"{live.myid}@goofish", receivers)
        text = json.loads(base64.b64decode(frame["body"][0]["content"]["custom"]["data"]))
        self.assertEqual(text, {"contentType": 1, "text": {"text": "你好"}})


class TestManualMode(OfflineTestCase):
    def test_toggle_and_timeout(self):
        live = make_live()
        self.assertFalse(live.is_manual_mode("70001"))
        self.assertEqual(live.toggle_manual_mode("70001"), "manual")
        self.assertTrue(live.is_manual_mode("70001"))
        # 超时自动退出
        live.manual_mode_timestamps["70001"] -= live.manual_mode_timeout + 1
        self.assertFalse(live.is_manual_mode("70001"))
        self.assertEqual(live.toggle_manual_mode("70001"), "manual")
        self.assertEqual(live.toggle_manual_mode("70001"), "auto")


class TestPaceReply(OfflineTestCase):
    def test_min_interval(self):
        live = make_live()
        live.reply_min_interval = 0.3
        start = time.monotonic()
        asyncio.run(live.pace_reply("66001"))  # 第一次立即通过
        asyncio.run(live.pace_reply("66001"))  # 第二次应补足间隔
        elapsed = time.monotonic() - start
        self.assertGreaterEqual(elapsed, 0.28)


class TestItemSnapshot(OfflineTestCase):
    def test_build_item_description(self):
        live = make_live()
        snapshot = json.loads(live.build_item_description({
            "title": "蓝牙耳机",
            "desc": "自用闲置",
            "soldPrice": 2900,
            "quantity": 1,
            "skuList": [{"price": 2900, "quantity": 1, "propertyList": [{"valueText": "黑色"}]}],
        }))
        self.assertEqual(snapshot["title"], "蓝牙耳机")
        self.assertEqual(snapshot["price_range"], "¥29.0")
        self.assertEqual(snapshot["sku_details"][0]["spec"], "黑色")

    def test_missing_or_invalid_snapshot_values_cannot_invent_sold_stock_or_zero_price(self):
        live = make_live()
        for values in ({}, {"quantity": -1, "soldPrice": "invalid"}, {"quantity": None, "soldPrice": float("nan")}):
            snapshot = json.loads(live.build_item_description({"title": "耳机", **values}))
            self.assertIsNone(snapshot["total_stock"])
            self.assertIsNone(snapshot["price_range"])
        sold = json.loads(live.build_item_description({"quantity": 0, "soldPrice": "29.50"}))
        self.assertEqual(sold["total_stock"], 0)
        self.assertEqual(sold["price_range"], "¥29.5")


class TestDisclaimer(OfflineTestCase):
    def test_disclaimer_once_per_chat(self):
        live = make_live()
        first = live.apply_disclaimer("70001", "耳机有货")
        second = live.apply_disclaimer("70001", "包邮发出")
        other = live.apply_disclaimer("70002", "另一会话")
        self.assertTrue(first.endswith(live.disclaimer_text))
        # 同一会话仅首条追加
        self.assertEqual(second, "包邮发出")
        # 不同会话独立计算
        self.assertTrue(other.endswith(live.disclaimer_text))

    def test_disclaimer_disabled_by_empty_env(self):
        live = make_live()
        live.disclaimer_text = ""
        self.assertEqual(live.apply_disclaimer("70001", "耳机有货"), "耳机有货")


class TestHandoffPause(OfflineTestCase):
    def test_bridge_handoff_flag_pauses_auto_reply(self):
        """BRIDGE 模式收到 handoffRequested=true 时应暂停该会话自动回复。"""
        server = start_mock_server()
        try:
            host, port = server.server_address
            live = make_live()
            live.mode = "BRIDGE"
            # 离线测试不触网：跳过商品快照（真实路径会调闲鱼 API 并可能回写 .env cookie）
            live.get_item_snapshot = lambda item_id: None
            live.cs_client = CsAgentClient(f"http://{host}:{port}", "svc-token", timeout=5)

            server.state["handoff_flag"] = False
            reply, handoff = asyncio.run(live.compose_reply("66001", "90111", "在吗"))
            self.assertEqual(reply, "ok:在吗")
            self.assertFalse(handoff)

            server.state["handoff_flag"] = True
            reply, handoff = asyncio.run(live.compose_reply("66002", "90111", "我要人工"))
            self.assertTrue(handoff)
            # 侧车按标志位进入人工接管（模拟 handle_message 的 X4 分支）
            live.enter_manual_mode("70002")
            self.assertTrue(live.is_manual_mode("70002"))
            # 卖家发句号恢复自动回复
            live.toggle_manual_mode("70002")
            self.assertFalse(live.is_manual_mode("70002"))
        finally:
            server.shutdown()
            server.server_close()


class TestCsAgentClient(OfflineTestCase):
    def setUp(self):
        self.server = start_mock_server()
        host, port = self.server.server_address
        self.client = CsAgentClient(f"http://{host}:{port}", "svc-token", timeout=5)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def test_register_and_chat(self):
        data = self.client.chat("66001", "90111", "在吗", item_snapshot='{"title":"耳机"}')
        self.assertEqual(data["reply"], "ok:在吗")
        self.assertFalse(data["handoffRequested"])
        sessions = [c for c in self.server.state["calls"] if c["path"] == "/api/xianyu/sessions"]
        self.assertEqual(len(sessions), 1)
        self.assertEqual(sessions[0]["body"]["itemSnapshot"], '{"title":"耳机"}')
        self.assertEqual(sessions[0]["body"]["buyerId"], "66001")
        chats = [c for c in self.server.state["calls"] if c["path"] == "/api/chat/send"]
        self.assertEqual(len(chats), 1)
        self.assertEqual(chats[0]["auth"], "Bearer jwt-1")
        self.assertEqual(chats[0]["body"]["conversationId"], "xianyu_66001_90111")

    def test_session_cached_across_messages(self):
        self.client.chat("66001", "90111", "一", item_snapshot="s")
        self.client.chat("66001", "90111", "二")
        sessions = [c for c in self.server.state["calls"] if c["path"] == "/api/xianyu/sessions"]
        self.assertEqual(len(sessions), 1)  # 第二条消息复用会话

    def test_expired_buyer_jwt_reregisters_once(self):
        self.server.state["expire_token"] = "jwt-1"
        data = self.client.chat("66001", "90111", "在吗")  # jwt-1 过期 → 重注册拿 jwt-2
        self.assertEqual(data["reply"], "ok:在吗")
        sessions = [c for c in self.server.state["calls"] if c["path"] == "/api/xianyu/sessions"]
        self.assertEqual(len(sessions), 2)

    def test_missing_session_endpoint_raises(self):
        with self.assertRaises(ConnectionError):
            self.client._post("/api/nope", {}, "svc-token")


class TestXianyuJavaIntegration(OfflineTestCase):
    """BRIDGE 闭环：409 静默对齐、句号经 Java 确认、卖家消息同步、事实推送。"""

    def setUp(self):
        self.server = start_mock_server()
        host, port = self.server.server_address
        self.live = make_live()
        self.live.mode = "BRIDGE"
        # 离线不打闲鱼 API：默认让快照拉取失败（真实 get_item_snapshot 路径仍被执行）
        self.live.xianyu.get_item_info = lambda item_id: {"error": "offline"}
        self.live.cs_client = CsAgentClient(f"http://{host}:{port}", "svc-token", timeout=5)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def test_chat_409_maps_to_silence_and_manual_alignment(self):
        """Java 409（卖家接管）：reply=None + handoff=True，侧车静默并对齐本地人工模式。"""
        # 先成功一条，让会话注册进 mock（拿到 conversationId）
        reply, handoff = asyncio.run(self.live.compose_reply("66001", "90111", "在吗"))
        self.assertEqual(reply, "ok:在吗")
        self.assertFalse(handoff)
        conversation_id = "xianyu_66001_90111"
        self.server.state["paused_conversation"] = conversation_id

        reply, handoff = asyncio.run(self.live.compose_reply("66001", "90111", "还在吗"))
        self.assertIsNone(reply)  # 绝不把 409 当故障话术发出去
        self.assertTrue(handoff)

        # handle_message 语义：handoff=True + 无回复 → 本地对齐人工模式
        chat_id = "70001"
        asyncio.run(self._simulate_buyer_turn(chat_id, "66001", "90111", "还在吗"))
        self.assertTrue(self.live.is_manual_mode(chat_id))

    def test_period_toggle_resumes_only_after_java_confirm(self):
        """句号恢复必须经 Java manual-mode 确认；Java 拒绝时回滚本地保持人工。"""
        chat_id, buyer_id, item_id = "70002", "66001", "90111"
        self.live._chat_buyers[chat_id] = {"buyerId": buyer_id, "itemId": item_id}

        # 进入人工：本地先进入，Java 同步成功
        asyncio.run(process_seller_event(self.live, chat_id, item_id, "。"))
        self.assertTrue(self.live.is_manual_mode(chat_id))
        self.assertTrue(self.server.state["manual_mode"])

        # 恢复自动：Java 确认成功 → 本地恢复
        asyncio.run(process_seller_event(self.live, chat_id, item_id, "。"))
        self.assertFalse(self.live.is_manual_mode(chat_id))
        self.assertFalse(self.server.state["manual_mode"])

        # Java 挂掉（manual-mode 端点不可达）：恢复失败回滚本地，保持人工模式等卖家重试
        self.live.enter_manual_mode(chat_id)
        original_post = self.live.cs_client._post

        def failing_manual_mode(path, payload, token):
            if path == "/api/xianyu/conversations/manual-mode":
                raise ConnectionError("endpoint down")
            return original_post(path, payload, token)

        self.live.cs_client._post = failing_manual_mode
        with self.assertRaises(ConnectionError):
            asyncio.run(process_seller_event(self.live, chat_id, item_id, "。"))
        self.assertTrue(self.live.is_manual_mode(chat_id))  # 回滚：仍人工

    def test_seller_message_is_forwarded_to_java_history(self):
        """卖家人工回复同步进 Java 会话历史（买家在闲鱼侧已看到原文）。"""
        chat_id, buyer_id, item_id = "70003", "66002", "90112"
        self.live._chat_buyers[chat_id] = {"buyerId": buyer_id, "itemId": item_id}
        asyncio.run(process_seller_event(self.live, chat_id, item_id, "240拿走，已经改好价了"))

        messages = self.server.state["seller_messages"]
        self.assertEqual(len(messages), 1)
        self.assertEqual(messages[0]["buyerId"], buyer_id)
        self.assertEqual(messages[0]["itemId"], item_id)
        self.assertEqual(messages[0]["text"], "240拿走，已经改好价了")
        self.assertFalse(messages[0]["resumeBot"])  # 只同步历史，不顺手恢复机器人

    def test_item_facts_pushed_after_cache_ttl_expiry(self):
        """快照 TTL 过期重新拉取时，价格/库存热更新推送到 Java（改价/售出对齐现势）。"""
        item_id = "90113"
        self.live.item_cache_ttl = 0  # 立即过期，强制重拉

        def fake_api(item_id):
            return {"data": {"itemDO": {
                "title": "蓝牙耳机",
                "desc": "自用闲置",
                "soldPrice": 29000,   # 分 → ¥290
                "quantity": 0,        # 售出
                "skuList": [{"price": 29000, "quantity": 0, "propertyList": []}],
            }}}

        self.live.xianyu.get_item_info = fake_api
        snapshot = self.live.get_item_snapshot(item_id)
        self.assertIn('"total_stock": 0', snapshot)

        facts = self.server.state["facts"]
        self.assertEqual(len(facts), 1)
        self.assertEqual(facts[0]["path"], f"/api/xianyu/items/{item_id}/facts")
        self.assertEqual(facts[0]["body"]["price"], "¥290.0")
        self.assertEqual(facts[0]["body"]["stock"], 0)

    async def _simulate_buyer_turn(self, chat_id, buyer_id, item_id, message):
        """复用 handle_message 的 handoff 语义（无回复 + handoff → 本地对齐人工）。"""
        reply, handoff = await self.live.compose_reply(buyer_id, item_id, message)
        if not reply and handoff:
            self.live.enter_manual_mode(chat_id)


class TestRestartRecovery(OfflineTestCase):
    """侧车重启恢复（评审三轮问题 2）：映射与人工模式落盘，重启后卖家操作仍同步 Java。"""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.state_file = str(Path(self.tmp.name) / "sidecar-state.json")
        self.server = start_mock_server()
        host, port = self.server.server_address
        self.live = self._make_bridge_live(f"http://{host}:{port}")

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.tmp.cleanup()

    def _make_bridge_live(self, base_url):
        live = make_live()
        live.mode = "BRIDGE"
        live.state_file = self.state_file
        live.xianyu.get_item_info = lambda item_id: {"error": "offline"}
        live.cs_client = CsAgentClient(base_url, "svc-token", timeout=5)
        live._load_state()
        return live

    def _restart(self):
        """模拟侧车重启：全新实例，同一状态文件与 Java。"""
        host, port = self.server.server_address
        return self._make_bridge_live(f"http://{host}:{port}")

    def test_seller_message_and_toggle_survive_restart(self):
        """重启后买家未再发言时，卖家人工回复与句号仍同步 Java（旧版为 0 次调用）。"""
        chat_id, buyer_id, item_id = "old-chat", "66001", "90111"
        # 运行期：买家消息建立映射，卖家句号进入人工（均落盘）
        self.live._chat_buyers[chat_id] = {"buyerId": buyer_id, "itemId": item_id}
        self.live.enter_manual_mode(chat_id)

        restarted = self._restart()
        self.assertEqual(
            restarted._chat_buyers.get(chat_id), {"buyerId": buyer_id, "itemId": item_id}
        )
        self.assertTrue(restarted.is_manual_mode(chat_id))

        # 重启后卖家人工回复：仍同步进 Java 历史
        asyncio.run(process_seller_event(restarted, chat_id, item_id, "240成交，我已经改好了"))
        messages = self.server.state["seller_messages"]
        self.assertEqual(len(messages), 1)
        self.assertEqual(messages[0]["buyerId"], buyer_id)
        self.assertEqual(messages[0]["text"], "240成交，我已经改好了")

        # 重启后卖家句号（人工→自动）：仍同步 Java 并本地恢复
        asyncio.run(process_seller_event(restarted, chat_id, item_id, "。"))
        self.assertFalse(restarted.is_manual_mode(chat_id))
        self.assertFalse(self.server.state["manual_mode"])

        # 再发一次句号（自动→人工）：方向同样同步 Java
        asyncio.run(process_seller_event(restarted, chat_id, item_id, "。"))
        self.assertTrue(restarted.is_manual_mode(chat_id))
        self.assertTrue(self.server.state["manual_mode"])

    def test_manual_state_aligned_with_java_after_restart(self):
        """重启恢复后按 Java 暂停状态对齐本地（Java 是事实源，双向翻转）。"""
        chat_id, buyer_id, item_id = "c1", "66001", "90111"
        self.live._chat_buyers[chat_id] = {"buyerId": buyer_id, "itemId": item_id}
        self.live._save_state()

        # 落盘=自动，Java=人工（重启窗口期内卖家从 Java 侧接管）→ 本地进入人工
        self.server.state["get_manual_mode"][f"{buyer_id}|{item_id}"] = True
        restarted = self._restart()
        asyncio.run(restarted.align_manual_state_with_java())
        self.assertTrue(restarted.is_manual_mode(chat_id))

        # 落盘=人工，Java=自动 → 本地恢复自动
        restarted.enter_manual_mode(chat_id)
        self.server.state["get_manual_mode"][f"{buyer_id}|{item_id}"] = False
        asyncio.run(restarted.align_manual_state_with_java())
        self.assertFalse(restarted.is_manual_mode(chat_id))

    def test_alignment_keeps_local_state_on_java_404(self):
        """Java 404（会话从未到达 Java）：保留本地落盘状态，不误翻转。"""
        chat_id, buyer_id, item_id = "c2", "66002", "90112"
        self.live._chat_buyers[chat_id] = {"buyerId": buyer_id, "itemId": item_id}
        self.live.enter_manual_mode(chat_id)
        self.server.state["get_manual_mode"][f"{buyer_id}|{item_id}"] = "missing"

        restarted = self._restart()
        asyncio.run(restarted.align_manual_state_with_java())
        self.assertTrue(restarted.is_manual_mode(chat_id))

    def test_echo_mode_never_writes_state_file(self):
        """已有状态文件和空目录下，ECHO 模式都不能创建或覆盖状态。"""
        for existing in (False, True):
            with self.subTest(existing=existing), tempfile.TemporaryDirectory() as directory:
                state_file = Path(directory) / "sidecar-state.json"
                original = b'{"existing": "bridge state"}'
                if existing:
                    state_file.write_bytes(original)
                with patch.dict("os.environ", {"SIDECAR_STATE_FILE": str(state_file)}):
                    echo = make_live()
                    self.assertIsNone(echo.state_file)
                    echo.toggle_manual_mode("70009")
                if existing:
                    self.assertEqual(original, state_file.read_bytes())
                else:
                    self.assertFalse(state_file.exists())


class TestFactsSyncGuard(OfflineTestCase):
    """事实同步闸门（三轮问题 3 + 四轮加固）：已读到但未同步成功的事实进入
    pending_facts（落盘），TTL 到期、平台读取失败、侧车重启都不丢；期间一律
    暂缓自动报价（含改价——不以"库存大于零"放行），成功同步后恢复。"""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.state_file = str(Path(self.tmp.name) / "sidecar-state.json")
        self.server = start_mock_server()
        host, port = self.server.server_address
        self.live = self._bridge_live()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.tmp.cleanup()

    def _bridge_live(self):
        host, port = self.server.server_address
        live = make_live()
        live.mode = "BRIDGE"
        live.state_file = self.state_file
        live.xianyu.get_item_info = lambda item_id: {"error": "offline"}
        live.cs_client = CsAgentClient(f"http://{host}:{port}", "svc-token", timeout=5)
        live._load_state()  # __init__ 时 state_file 尚未指定，这里手动恢复
        return live

    def _restart(self):
        """模拟侧车重启：全新实例（进程内缓存清空），同一状态文件与 Java。"""
        return self._bridge_live()

    def _fake_platform(self, quantity, price=29000, live=None):
        """平台快照假实现：价格（分）与库存由参数控制。"""
        target = live or self.live
        def fake_api(item_id):
            return {"data": {"itemDO": {
                "title": "蓝牙耳机",
                "desc": "自用闲置",
                "soldPrice": price,
                "quantity": quantity,
                "skuList": [{"price": price, "quantity": quantity, "propertyList": []}],
            }}}
        target.xianyu.get_item_info = fake_api

    def _flaky_push(self, fail, live=None):
        """注入推送失败：fail['on']=True 时直接返回 False（不发 HTTP）。"""
        target = live or self.live
        original = target.cs_client.push_item_facts

        def flaky(item_id, price=None, stock=None):
            if fail["on"]:
                return False
            return original(item_id, price=price, stock=stock)

        target.cs_client.push_item_facts = flaky

    def _chat_calls(self):
        return [c for c in self.server.state["calls"] if c["path"] == "/api/chat/send"]

    def test_push_failure_retries_on_next_message_within_ttl(self):
        """推送失败保留待同步事实；TTL 内买家下一条消息前重试成功，回复照常。"""
        self._fake_platform(quantity=1)
        fail = {"on": True}
        self._flaky_push(fail)

        self.live.item_cache_ttl = 0
        snapshot = self.live.get_item_snapshot("90114")  # 拉取成功、推送失败
        self.assertIn('"total_stock": 1', snapshot)
        self.assertFalse(self.live.item_facts_synced["90114"])
        self.assertEqual(self.live.pending_facts["90114"], {"price": "¥290.0", "stock": 1})
        self.assertEqual(len(self.server.state["facts"]), 0)  # Java 未收到

        self.live.item_cache_ttl = 300  # TTL 内不再重拉平台
        fail["on"] = False
        reply, handoff = asyncio.run(self.live.compose_reply("66001", "90114", "250卖吗"))
        self.assertEqual(reply, "ok:250卖吗")
        self.assertFalse(handoff)
        self.assertTrue(self.live.item_facts_synced["90114"])
        self.assertNotIn("90114", self.live.pending_facts)  # 成功同步即移除
        # 重试发生在买家消息出站前，Java 恰好收到一次事实推送
        facts = self.server.state["facts"]
        self.assertEqual(len(facts), 1)
        self.assertEqual(facts[0]["body"]["stock"], 1)

    def test_sold_out_unsynced_suppresses_agent_reply(self):
        """平台已售（库存0）但推送失败：暂缓自动回复，绝不转发 Java 按旧库存报价。"""
        self._fake_platform(quantity=0)
        fail = {"on": True}
        self._flaky_push(fail)

        self.live.item_cache_ttl = 0
        self.live.get_item_snapshot("90115")  # 读到售出，推送失败
        self.live.item_cache_ttl = 300

        with self.assertRaises(ConnectionError):
            asyncio.run(self.live.compose_reply("66001", "90115", "250可以成交"))
        # 关键断言：根本没有调 /api/chat/send（不按旧库存报价）
        self.assertEqual(len(self._chat_calls()), 0)

        # 恢复推送后，买家下一条消息自动恢复（无需等 TTL）
        fail["on"] = False
        reply, _ = asyncio.run(self.live.compose_reply("66001", "90115", "还在卖吗"))
        self.assertEqual(reply, "ok:还在卖吗")
        self.assertEqual(self.server.state["facts"][-1]["body"]["stock"], 0)

    def test_price_change_unsynced_suppresses_agent_reply(self):
        """已有会话卖家改价但推送失败：暂缓自动报价，不按旧价承诺成交（四轮反转放行行为）。"""
        # 先以 ¥290 正常同步：会话已有基准事实
        self._fake_platform(quantity=1)
        self.live.item_cache_ttl = 0
        self.live.get_item_snapshot("90116")
        self.assertEqual(len(self.server.state["facts"]), 1)
        self.assertEqual(self.server.state["facts"][0]["body"]["price"], "¥290.0")

        # 卖家改价 ¥250：TTL 过期重读到新事实，但推送失败 → 待同步事实保留
        fail = {"on": True}
        self._flaky_push(fail)
        self._fake_platform(quantity=1, price=25000)
        snapshot = self.live.get_item_snapshot("90116")
        self.assertIn("¥250.0", snapshot)
        self.assertEqual(self.live.pending_facts["90116"], {"price": "¥250.0", "stock": 1})
        self.live.item_cache_ttl = 300

        # 买家议价：暂缓——库存>0 不再放行（不能按旧价 ¥290 承诺成交价）
        with self.assertRaises(ConnectionError):
            asyncio.run(self.live.compose_reply("66001", "90116", "270可以吗"))
        self.assertEqual(len(self._chat_calls()), 0)

        # 推送恢复：买家下一条消息重试成功，自动报价恢复且以新价同步
        fail["on"] = False
        reply, _ = asyncio.run(self.live.compose_reply("66001", "90116", "270可以吗"))
        self.assertEqual(reply, "ok:270可以吗")
        self.assertEqual(self.server.state["facts"][-1]["body"]["price"], "¥250.0")
        self.assertNotIn("90116", self.live.pending_facts)

    def test_sold_out_unsynced_survives_ttl_refresh_failure(self):
        """已售未同步后 TTL 到期、平台读取失败：旧快照与拦截保留，不放行（四轮问题 1）。"""
        self._fake_platform(quantity=0)
        fail = {"on": True}
        self._flaky_push(fail)
        self.live.item_cache_ttl = 0
        self.live.get_item_snapshot("90117")  # 读到售出，推送失败
        self.assertEqual(self.live.pending_facts["90117"]["stock"], 0)

        # TTL 到期后平台读取失败：旧快照不被清成 None，未同步事实保留
        self.live.xianyu.get_item_info = lambda item_id: {"error": "offline"}
        snapshot = self.live.get_item_snapshot("90117")
        self.assertIn('"total_stock": 0', snapshot)  # 旧快照仍在（修复前这里变 None）
        self.assertIn("90117", self.live.pending_facts)
        self.live.item_cache_ttl = 300

        # 闸门重试仍失败：继续拦截（修复前缺失库存默认 1 → 放行，chat 被调用）
        with self.assertRaises(ConnectionError):
            asyncio.run(self.live.compose_reply("66001", "90117", "250可以成交"))
        self.assertEqual(len(self._chat_calls()), 0)

        # 推送恢复：重试成功解除拦截，回复照常（带已售事实）
        self._fake_platform(quantity=0)
        fail["on"] = False
        reply, _ = asyncio.run(self.live.compose_reply("66001", "90117", "250可以成交"))
        self.assertEqual(reply, "ok:250可以成交")
        self.assertEqual(self.server.state["facts"][-1]["body"]["stock"], 0)
        self.assertNotIn("90117", self.live.pending_facts)

    def test_unsynced_facts_survive_sidecar_restart(self):
        """未同步期间侧车重启：待同步事实与拦截从状态文件恢复，成功同步后才放行。"""
        self._fake_platform(quantity=1, price=25000)
        fail = {"on": True}
        self._flaky_push(fail)
        self.live.item_cache_ttl = 0
        self.live.get_item_snapshot("90118")  # 改价 ¥250 读到，推送失败（落盘）
        self.assertEqual(self.live.pending_facts["90118"], {"price": "¥250.0", "stock": 1})

        # 重启：新实例、同一状态文件——进程内快照缓存清空，只有待同步事实在
        restarted = self._restart()
        self.assertEqual(
            restarted.pending_facts.get("90118"), {"price": "¥250.0", "stock": 1}
        )
        self.assertFalse(restarted.item_facts_synced["90118"])

        # 重启后推送仍失败：重拉平台成功但同步失败，买家消息照旧拦截
        fail2 = {"on": True}
        self._flaky_push(fail2, live=restarted)
        self._fake_platform(quantity=1, price=25000, live=restarted)
        restarted.item_cache_ttl = 300
        with self.assertRaises(ConnectionError):
            asyncio.run(restarted.compose_reply("66001", "90118", "260出吗"))
        self.assertEqual(len(self._chat_calls()), 0)

        # 推送恢复：下一条消息重试成功，自动报价恢复
        fail2["on"] = False
        reply, _ = asyncio.run(restarted.compose_reply("66001", "90118", "260出吗"))
        self.assertEqual(reply, "ok:260出吗")
        self.assertEqual(self.server.state["facts"][-1]["body"]["price"], "¥250.0")
        self.assertNotIn("90118", restarted.pending_facts)


if __name__ == "__main__":
    unittest.main(verbosity=2)


class TestImpersonationFallback(OfflineTestCase):
    """TLS 指纹伪装改造（2026-09-09）的回归测试：不触网。

    阿里 SM 风控按 TLS 指纹拦截 Python requests，XianyuApis 会话须优先用
    curl_cffi（Chrome 指纹）；未安装时回退 requests 且不崩。另验证两种
    CookieJar 迭代协议在 _cookie_pairs 下都产出 (name, value)。
    """

    def test_session_prefers_curl_cffi_when_available(self):
        import XianyuApis as xianyu_apis_module

        api = xianyu_apis_module.XianyuApis()
        if xianyu_apis_module.impersonated_requests is not None:
            self.assertIsNot(api.session.__class__.__module__, "requests.sessions")
        else:
            # 回退分支：仍是 requests.Session，导入与构造都不报错
            import requests as plain_requests

            self.assertIsInstance(api.session, plain_requests.Session)

    def test_cookie_pairs_handles_both_jar_protocols(self):
        import requests as plain_requests
        import XianyuApis as xianyu_apis_module
        from XianyuApis import _cookie_pairs

        # requests 协议：迭代产出 Cookie 对象
        requests_jar = plain_requests.cookies.RequestsCookieJar()
        requests_jar.set("unb", "123", domain=".goofish.com")
        self.assertEqual(
            sorted(_cookie_pairs(requests_jar)), [("unb", "123")]
        )

        # curl_cffi 协议：迭代产出 cookie 名字符串
        if xianyu_apis_module.impersonated_requests is None:
            self.skipTest("curl_cffi 未安装，跳过该协议分支")
        cffi_session = xianyu_apis_module.impersonated_requests.Session(
            impersonate="chrome"
        )
        cffi_session.cookies.update({"unb": "123", "cookie2": "xyz"})
        self.assertEqual(
            sorted(_cookie_pairs(cffi_session.cookies)),
            [("cookie2", "xyz"), ("unb", "123")],
        )

    def test_clear_duplicate_cookies_unifies_domains(self):
        """跨域散落的 cookie 清理后统一到 .goofish.com，h5api/passport 都能带上。"""
        from XianyuApis import XianyuApis

        api = XianyuApis()
        jar = api.session.cookies
        jar.set("unb", "123", domain=".taobao.com")
        jar.set("cookie2", "old", domain=".goofish.com")
        jar.set("cookie2", "new", domain=".taobao.com")
        api.clear_duplicate_cookies()
        names = {name for name, _ in _cookie_pairs_for_test(api)}
        self.assertIn("unb", names)
        self.assertIn("cookie2", names)
        # 上游逆序遍历取同名首个，等价于 dict() 正序后写覆盖：都保留迭代序中
        # 最后出现的同名值。requests 按域分组迭代，此处最后是 .goofish.com 的 old。
        values = {name: value for name, value in _cookie_pairs_for_test(api)}
        self.assertEqual(values.get("cookie2"), "old")
        self.assertEqual(values.get("unb"), "123")


def _cookie_pairs_for_test(api):
    from XianyuApis import _cookie_pairs

    return _cookie_pairs(api.session.cookies)
