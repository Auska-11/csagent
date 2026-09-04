"""
侧车离线测试：不触网、不需要 cookie、不依赖 Java 服务。

运行：python tests/test_offline.py
"""

import asyncio
import base64
import json
import sys
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from main import CsAgentClient, XianyuLive  # noqa: E402
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
            self._json(200, {"reply": f"ok:{payload['message']}"})
        else:
            self._json(404, {"error": "not found"})


def start_mock_server():
    server = ThreadingHTTPServer(("127.0.0.1", 0), MockHandler)
    server.state = {
        "calls": [],
        "service_token": "svc-token",
        "registered": 0,
        "expire_token": None,
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


def make_live():
    return XianyuLive(DUMMY_COOKIES)


class TestUpstreamUtils(unittest.TestCase):
    def test_trans_cookies(self):
        cookies = trans_cookies(DUMMY_COOKIES)
        self.assertEqual(cookies["unb"], "12345")
        self.assertEqual(cookies["cookie2"], "xyz")


class TestEchoMode(unittest.TestCase):
    def test_compose_reply_echo(self):
        live = make_live()
        reply = asyncio.run(live.compose_reply("66001", "90111", "蓝牙耳机还在吗"))
        self.assertEqual(reply, "[echo:xianyu_66001_90111] 蓝牙耳机还在吗")

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


class TestManualMode(unittest.TestCase):
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


class TestPaceReply(unittest.TestCase):
    def test_min_interval(self):
        live = make_live()
        live.reply_min_interval = 0.3
        start = time.monotonic()
        asyncio.run(live.pace_reply("66001"))  # 第一次立即通过
        asyncio.run(live.pace_reply("66001"))  # 第二次应补足间隔
        elapsed = time.monotonic() - start
        self.assertGreaterEqual(elapsed, 0.28)


class TestItemSnapshot(unittest.TestCase):
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


class TestCsAgentClient(unittest.TestCase):
    def setUp(self):
        self.server = start_mock_server()
        host, port = self.server.server_address
        self.client = CsAgentClient(f"http://{host}:{port}", "svc-token", timeout=5)

    def tearDown(self):
        self.server.shutdown()

    def test_register_and_chat(self):
        reply = self.client.chat("66001", "90111", "在吗", item_snapshot='{"title":"耳机"}')
        self.assertEqual(reply, "ok:在吗")
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
        reply = self.client.chat("66001", "90111", "在吗")  # jwt-1 过期 → 重注册拿 jwt-2
        self.assertEqual(reply, "ok:在吗")
        sessions = [c for c in self.server.state["calls"] if c["path"] == "/api/xianyu/sessions"]
        self.assertEqual(len(sessions), 2)

    def test_missing_session_endpoint_raises(self):
        with self.assertRaises(ConnectionError):
            self.client._post("/api/nope", {}, "svc-token")


if __name__ == "__main__":
    unittest.main(verbosity=2)
