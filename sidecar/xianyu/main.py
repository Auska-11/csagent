"""
闲鱼侧车（csagent 项目第三阶段 X1/X3）：无脑协议转发器。

本文件从 XianyuAutoAgent (https://github.com/shaxiu/XianyuAutoAgent, GPL-3.0)
的 main.py 移植协议层骨架：WebSocket 连接/注册、心跳、token 刷新与重连、
消息解密与过滤、消息发送。大脑（LLM 意图路由 + 提示词 + SQLite 上下文 +
议价计数）全部砍掉。裁剪与改动点逐条记录在 PROVENANCE.md。

两种运行模式（SIDECAR_MODE）：
  ECHO   收到买家消息后本地回显（X1：只验证协议链路，不依赖 Java 服务）
  BRIDGE 买家消息转发到 Java 客服 Agent，回复发回闲鱼（X3 起）

BRIDGE 契约（Java 侧 X2 实现）：
  1) POST {CS_AGENT_URL}/api/xianyu/sessions     Authorization: Bearer <服务令牌>
       body  {"buyerId": "...", "itemId": "...", "itemSnapshot": "..."(可选,首次)}
       resp  {"userId": "USER-XY-xxxx", "token": "<该买家 JWT>", "conversationId": "xianyu_..._..."}
     —— buyerId 幂等映射为合成用户；itemSnapshot 首次携带时 Java 侧生成商品知识文档。
  2) POST {CS_AGENT_URL}/api/chat/send           Authorization: Bearer <该买家 JWT>
       body  {"conversationId": "...", "message": "..."}
       resp  {"reply": "..."}
     —— 与现有聊天端点同构：conversationId 原样透传，身份只信 JWT（body userId 可省略）。
"""

import asyncio
import base64
import hashlib  # noqa: F401  (X2 若改为侧车本地映射时使用，当前映射在 Java 侧)
import json
import os
import random
import sys
import time

import websockets
from dotenv import load_dotenv
from loguru import logger

from utils.xianyu_utils import (
    generate_device_id,
    generate_mid,
    generate_uuid,
    trans_cookies,
    decrypt,
)
from XianyuApis import XianyuApis


class CsAgentClient:
    """Java 客服 Agent 的 HTTP 客户端（BRIDGE 模式）。

    会话注册结果按 (buyerId, itemId) 缓存；买家 JWT 过期时自动重注册一次。
    本类的方法是同步阻塞的，调用方须用 asyncio.to_thread 包裹，避免饿死心跳任务。
    """

    def __init__(self, base_url, service_token, timeout=30.0):
        import requests

        self.base_url = base_url.rstrip("/")
        self.service_token = service_token
        self.timeout = timeout
        self.session = requests.Session()
        self._sessions = {}  # (buyer_id, item_id) -> {"userId","token","conversationId"}

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
        resp.raise_for_status()
        return resp.json()

    def ensure_session(self, buyer_id, item_id, item_snapshot=None):
        key = (buyer_id, item_id)
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
        self._sessions[key] = session
        logger.info(f"会话注册成功: {session['userId']} / {session['conversationId']}")
        return session

    def chat(self, buyer_id, item_id, message, item_snapshot=None):
        """注册（如需）→ /api/chat/send → 完整响应 dict（reply + handoffRequested）。

        handoffRequested=true 表示 Java 侧工单创建成功、会话终态 HANDED_OFF，
        侧车据此暂停该会话自动回复（X4 双层转人工，docs-76 §5.2）。
        """
        try:
            session = self.ensure_session(buyer_id, item_id, item_snapshot)
            data = self._post(
                "/api/chat/send",
                {"conversationId": session["conversationId"], "message": message},
                session["token"],
            )
        except PermissionError:
            # 买家 JWT 过期或服务令牌失效：丢缓存后重注册一次
            self._sessions.pop((buyer_id, item_id), None)
            session = self.ensure_session(buyer_id, item_id)
            data = self._post(
                "/api/chat/send",
                {"conversationId": session["conversationId"], "message": message},
                session["token"],
            )
        reply = data.get("reply")
        if not isinstance(reply, str) or not reply.strip():
            raise ValueError(f"/api/chat/send 返回异常: {data}")
        return data


class XianyuLive:
    def __init__(self, cookies_str):
        self.xianyu = XianyuApis()
        self.base_url = "wss://wss-goofish.dingtalk.com/"
        self.cookies_str = cookies_str
        self.cookies = trans_cookies(cookies_str)
        self.xianyu.session.cookies.update(self.cookies)
        self.myid = self.cookies["unb"]
        self.device_id = generate_device_id(self.myid)

        # 运行模式与 Java 端点
        self.mode = os.getenv("SIDECAR_MODE", "ECHO").upper()
        self.fallback_reply = os.getenv("FALLBACK_REPLY", "").strip()

        # 心跳相关配置
        self.heartbeat_interval = int(os.getenv("HEARTBEAT_INTERVAL", "15"))  # 心跳间隔，默认15秒
        self.heartbeat_timeout = int(os.getenv("HEARTBEAT_TIMEOUT", "5"))     # 心跳超时，默认5秒
        self.last_heartbeat_time = 0
        self.last_heartbeat_response = 0
        self.heartbeat_task = None
        self.ws = None

        # Token刷新相关配置
        self.token_refresh_interval = int(os.getenv("TOKEN_REFRESH_INTERVAL", "3600"))  # Token刷新间隔，默认1小时
        self.token_retry_interval = int(os.getenv("TOKEN_RETRY_INTERVAL", "300"))       # Token重试间隔，默认5分钟
        self.last_token_refresh_time = 0
        self.current_token = None
        self.token_refresh_task = None
        self.connection_restart_flag = False  # 连接重启标志

        # 人工接管相关配置（协议层总开关：卖家本人在闲鱼 App 里发关键词切换；
        # 与 Java 侧 HANDOFF 业务级暂停构成双层转人工，见 docs-76 §5.2）
        self.manual_mode_conversations = set()  # 存储处于人工接管模式的会话ID
        self.manual_mode_timeout = int(os.getenv("MANUAL_MODE_TIMEOUT", "3600"))  # 人工接管超时时间，默认1小时
        self.manual_mode_timestamps = {}  # 记录进入人工模式的时间

        # 免责话术：每个会话首条自动回复追加一次，声明由 AI 生成（置空关闭）
        self.disclaimer_text = os.getenv(
            "DISCLAIMER_TEXT", "（本条由智能助手自动回复，如需人工服务请直接说明）"
        ).strip()
        self._disclaimer_shown = set()  # 已追加过免责话术的 chat_id

        # 消息过期时间配置
        self.message_expire_time = int(os.getenv("MESSAGE_EXPIRE_TIME", "300000"))  # 消息过期时间，默认5分钟

        # 人工接管关键词，从环境变量读取
        self.toggle_keywords = os.getenv("TOGGLE_KEYWORDS", "。")

        # 模拟人工输入配置
        self.simulate_human_typing = os.getenv("SIMULATE_HUMAN_TYPING", "False").lower() == "true"

        # 出站回复节流：同一买家两次回复的最小间隔（风控缓解，见 docs-76 §5.2）
        self.reply_min_interval = float(os.getenv("REPLY_MIN_INTERVAL", "3.5"))
        self._last_reply_at = {}  # buyer_id -> monotonic 时间戳

        # 商品信息进程内缓存（替代上游 SQLite 商品表；失败不阻塞回复）
        self.item_cache = {}

        # Java 客户端按需创建（ECHO 模式不依赖 Java）
        self.cs_client = None
        if self.mode == "BRIDGE":
            base_url = os.getenv("CS_AGENT_URL", "http://localhost:8080")
            service_token = os.getenv("CS_AGENT_TOKEN", "")
            if not service_token:
                raise SystemExit("BRIDGE 模式必须配置 CS_AGENT_TOKEN（X2 签发的侧车服务令牌）")
            self.cs_client = CsAgentClient(
                base_url, service_token, timeout=float(os.getenv("CS_AGENT_TIMEOUT", "30"))
            )
        elif self.mode != "ECHO":
            raise SystemExit(f"未知的 SIDECAR_MODE: {self.mode}（可选 ECHO / BRIDGE）")

    async def refresh_token(self):
        """刷新token"""
        try:
            logger.info("开始刷新token...")

            # 获取新token（如果Cookie失效，get_token会直接退出程序）
            token_result = self.xianyu.get_token(self.device_id)
            if "data" in token_result and "accessToken" in token_result["data"]:
                new_token = token_result["data"]["accessToken"]
                self.current_token = new_token
                self.last_token_refresh_time = time.time()
                logger.info("Token刷新成功")
                return new_token
            else:
                logger.error(f"Token刷新失败: {token_result}")
                return None

        except Exception as e:
            logger.error(f"Token刷新异常: {str(e)}")
            return None

    async def token_refresh_loop(self):
        """Token刷新循环"""
        while True:
            try:
                current_time = time.time()

                # 检查是否需要刷新token
                if current_time - self.last_token_refresh_time >= self.token_refresh_interval:
                    logger.info("Token即将过期，准备刷新...")

                    new_token = await self.refresh_token()
                    if new_token:
                        logger.info("Token刷新成功，准备重新建立连接...")
                        # 设置连接重启标志
                        self.connection_restart_flag = True
                        # 关闭当前WebSocket连接，触发重连
                        if self.ws:
                            await self.ws.close()
                        break
                    else:
                        logger.error("Token刷新失败，将在{}分钟后重试".format(self.token_retry_interval // 60))
                        await asyncio.sleep(self.token_retry_interval)
                        continue

                # 每分钟检查一次
                await asyncio.sleep(60)

            except Exception as e:
                logger.error(f"Token刷新循环出错: {e}")
                await asyncio.sleep(60)

    async def send_msg(self, ws, cid, toid, text):
        text_payload = {
            "contentType": 1,
            "text": {
                "text": text
            }
        }
        text_base64 = str(base64.b64encode(json.dumps(text_payload).encode("utf-8")), "utf-8")
        msg = {
            "lwp": "/r/MessageSend/sendByReceiverScope",
            "headers": {
                "mid": generate_mid()
            },
            "body": [
                {
                    "uuid": generate_uuid(),
                    "cid": f"{cid}@goofish",
                    "conversationType": 1,
                    "content": {
                        "contentType": 101,
                        "custom": {
                            "type": 1,
                            "data": text_base64
                        }
                    },
                    "redPointPolicy": 0,
                    "extension": {
                        "extJson": "{}"
                    },
                    "ctx": {
                        "appVersion": "1.0",
                        "platform": "web"
                    },
                    "mtags": {},
                    "msgReadStatusSetting": 1
                },
                {
                    "actualReceivers": [
                        f"{toid}@goofish",
                        f"{self.myid}@goofish"
                    ]
                }
            ]
        }
        await ws.send(json.dumps(msg))

    async def init(self, ws):
        # 如果没有token或者token过期，获取新token
        if not self.current_token or (time.time() - self.last_token_refresh_time) >= self.token_refresh_interval:
            logger.info("获取初始token...")
            await self.refresh_token()

        if not self.current_token:
            logger.error("无法获取有效token，初始化失败")
            raise Exception("Token获取失败")

        msg = {
            "lwp": "/reg",
            "headers": {
                "cache-header": "app-key token ua wv",
                "app-key": "444e9908a51d1cb236a27862abc769c9",
                "token": self.current_token,
                "ua": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36 DingTalk(2.1.5) OS(Windows/10) Browser(Chrome/133.0.0.0) DingWeb/2.1.5 IMPaaS DingWeb/2.1.5",
                "dt": "j",
                "wv": "im:3,au:3,sy:6",
                "sync": "0,0;0;0;",
                "did": self.device_id,
                "mid": generate_mid()
            }
        }
        await ws.send(json.dumps(msg))
        # 等待一段时间，确保连接注册完成
        await asyncio.sleep(1)
        msg = {"lwp": "/r/SyncStatus/ackDiff", "headers": {"mid": "5701741704675979 0"}, "body": [
            {"pipeline": "sync", "tooLong2Tag": "PNM,1", "channel": "sync", "topic": "sync", "highPts": 0,
             "pts": int(time.time() * 1000) * 1000, "seq": 0, "timestamp": int(time.time() * 1000)}]}
        await ws.send(json.dumps(msg))
        logger.info("连接注册完成")

    def is_chat_message(self, message):
        """判断是否为用户聊天消息"""
        try:
            return (
                isinstance(message, dict)
                and "1" in message
                and isinstance(message["1"], dict)  # 确保是字典类型
                and "10" in message["1"]
                and isinstance(message["1"]["10"], dict)  # 确保是字典类型
                and "reminderContent" in message["1"]["10"]
            )
        except Exception:
            return False

    def is_sync_package(self, message_data):
        """判断是否为同步包消息"""
        try:
            return (
                isinstance(message_data, dict)
                and "body" in message_data
                and "syncPushPackage" in message_data["body"]
                and "data" in message_data["body"]["syncPushPackage"]
                and len(message_data["body"]["syncPushPackage"]["data"]) > 0
            )
        except Exception:
            return False

    def is_typing_status(self, message):
        """判断是否为用户正在输入状态消息"""
        try:
            return (
                isinstance(message, dict)
                and "1" in message
                and isinstance(message["1"], list)
                and len(message["1"]) > 0
                and isinstance(message["1"][0], dict)
                and "1" in message["1"][0]
                and isinstance(message["1"][0]["1"], str)
                and "@goofish" in message["1"][0]["1"]
            )
        except Exception:
            return False

    def is_system_message(self, message):
        """判断是否为系统消息"""
        try:
            return (
                isinstance(message, dict)
                and "3" in message
                and isinstance(message["3"], dict)
                and "needPush" in message["3"]
                and message["3"]["needPush"] == "false"
            )
        except Exception:
            return False

    def is_bracket_system_message(self, message):
        """检查是否为带中括号的系统消息"""
        try:
            if not message or not isinstance(message, str):
                return False

            clean_message = message.strip()
            # 检查是否以 [ 开头，以 ] 结尾
            if clean_message.startswith("[") and clean_message.endswith("]"):
                logger.debug(f"检测到系统消息: {clean_message}")
                return True
            return False
        except Exception as e:
            logger.error(f"检查系统消息失败: {e}")
            return False

    def check_toggle_keywords(self, message):
        """检查消息是否包含切换关键词"""
        message_stripped = message.strip()
        return message_stripped in self.toggle_keywords

    def is_manual_mode(self, chat_id):
        """检查特定会话是否处于人工接管模式"""
        if chat_id not in self.manual_mode_conversations:
            return False

        # 检查是否超时
        current_time = time.time()
        if chat_id in self.manual_mode_timestamps:
            if current_time - self.manual_mode_timestamps[chat_id] > self.manual_mode_timeout:
                # 超时，自动退出人工模式
                self.exit_manual_mode(chat_id)
                return False

        return True

    def enter_manual_mode(self, chat_id):
        """进入人工接管模式"""
        self.manual_mode_conversations.add(chat_id)
        self.manual_mode_timestamps[chat_id] = time.time()

    def exit_manual_mode(self, chat_id):
        """退出人工接管模式"""
        self.manual_mode_conversations.discard(chat_id)
        if chat_id in self.manual_mode_timestamps:
            del self.manual_mode_timestamps[chat_id]

    def toggle_manual_mode(self, chat_id):
        """切换人工接管模式"""
        if self.is_manual_mode(chat_id):
            self.exit_manual_mode(chat_id)
            return "auto"
        else:
            self.enter_manual_mode(chat_id)
            return "manual"

    # ------------------------------------------------------------------
    # 回复生成（替代上游 XianyuReplyBot.generate_reply + ChatContextManager）
    # ------------------------------------------------------------------

    def format_price(self, price):
        """处理逻辑：标准化价格（分转元）"""
        try:
            return round(float(price) / 100, 2)
        except (ValueError, TypeError):
            # 遇到 None 或脏数据，默认返回 0
            return 0.0

    def build_item_description(self, item_info):
        """构建商品描述快照（仅供 Java 侧生成商品知识文档，不塞进聊天消息正文）"""
        # 处理 SKU 列表
        clean_skus = []
        raw_sku_list = item_info.get("skuList", [])

        for sku in raw_sku_list:
            # 提取规格文本
            specs = [p["valueText"] for p in sku.get("propertyList", []) if p.get("valueText")]
            spec_text = " ".join(specs) if specs else "默认规格"

            clean_skus.append({
                "spec": spec_text,
                "price": self.format_price(sku.get("price", 0)),
                "stock": sku.get("quantity", 0)
            })

        # 获取价格
        valid_prices = [s["price"] for s in clean_skus if s["price"] > 0]

        if valid_prices:
            min_price = min(valid_prices)
            max_price = max(valid_prices)
            if min_price == max_price:
                price_display = f"¥{min_price}"
            else:
                price_display = f"¥{min_price} - ¥{max_price}"  # 价格区间
        else:
            # 如果没有SKU价格，回退使用商品主价格
            main_price = round(float(item_info.get("soldPrice", 0)), 2)
            price_display = f"¥{main_price}"

        summary = {
            "title": item_info.get("title", ""),
            "desc": item_info.get("desc", ""),
            "price_range": price_display,
            "total_stock": item_info.get("quantity", 0),
            "sku_details": clean_skus
        }

        return json.dumps(summary, ensure_ascii=False)

    def get_item_snapshot(self, item_id):
        """商品信息快照（进程内缓存；获取失败返回 None，不阻塞回复链路）。"""
        if item_id in self.item_cache:
            return self.item_cache[item_id]
        api_result = self.xianyu.get_item_info(item_id)
        if "data" in api_result and "itemDO" in api_result["data"]:
            item_info = api_result["data"]["itemDO"]
            snapshot = self.build_item_description(item_info)
            self.item_cache[item_id] = snapshot
            return snapshot
        logger.warning(f"获取商品信息失败（继续无快照回复）: {api_result}")
        self.item_cache[item_id] = None
        return None

    def apply_disclaimer(self, chat_id, reply):
        """每个会话首条自动回复追加免责话术（置空 DISCLAIMER_TEXT 关闭）。"""
        if self.disclaimer_text and chat_id not in self._disclaimer_shown:
            self._disclaimer_shown.add(chat_id)
            return f"{reply}\n{self.disclaimer_text}"
        return reply

    async def compose_reply(self, buyer_id, item_id, send_message):
        """ECHO 本地回显；BRIDGE 转发 Java 客服 Agent（HTTP 放线程池，避免饿死心跳）。

        返回 (reply, handoff_requested)：handoff_requested=True 表示 Java 侧
        转人工成功，调用方应暂停该会话的自动回复。
        """
        if self.mode == "ECHO":
            return f"[echo:xianyu_{buyer_id}_{item_id}] {send_message}", False

        snapshot = await asyncio.to_thread(self.get_item_snapshot, item_id)
        try:
            data = await asyncio.to_thread(
                self.cs_client.chat, buyer_id, item_id, send_message, snapshot
            )
        except Exception as exc:
            logger.error(f"转发客服 Agent 失败: {exc}")
            return self.fallback_reply or None, False
        handoff = bool(data.get("handoffRequested", False))
        return data["reply"], handoff

    async def pace_reply(self, buyer_id):
        """出站节流：距该买家上次回复不足 REPLY_MIN_INTERVAL 秒时补足等待（上限10秒）。"""
        now = time.monotonic()
        wait = self.reply_min_interval - (now - self._last_reply_at.get(buyer_id, 0))
        if wait > 0:
            await asyncio.sleep(min(wait, 10.0))
        self._last_reply_at[buyer_id] = time.monotonic()

    async def handle_message(self, message_data, websocket):
        """处理所有类型的消息"""
        try:

            try:
                message = message_data
                ack = {
                    "code": 200,
                    "headers": {
                        "mid": message["headers"]["mid"] if "mid" in message["headers"] else generate_mid(),
                        "sid": message["headers"]["sid"] if "sid" in message["headers"] else "",
                    }
                }
                if "app-key" in message["headers"]:
                    ack["headers"]["app-key"] = message["headers"]["app-key"]
                if "ua" in message["headers"]:
                    ack["headers"]["ua"] = message["headers"]["ua"]
                if "dt" in message["headers"]:
                    ack["headers"]["dt"] = message["headers"]["dt"]
                await websocket.send(json.dumps(ack))
            except Exception:
                pass

            # 如果不是同步包消息，直接返回
            if not self.is_sync_package(message_data):
                return

            # 获取并解密数据
            sync_data = message_data["body"]["syncPushPackage"]["data"][0]

            # 检查是否有必要的字段
            if "data" not in sync_data:
                logger.debug("同步包中无data字段")
                return

            # 解密数据
            try:
                data = sync_data["data"]
                try:
                    data = base64.b64decode(data).decode("utf-8")
                    data = json.loads(data)
                    # logger.info(f"无需解密 message: {data}")
                    return
                except Exception:
                    decrypted_data = decrypt(data)
                    message = json.loads(decrypted_data)
            except Exception as e:
                logger.error(f"消息解密失败: {e}")
                return

            try:
                # 判断是否为订单状态消息（拍下/关闭/发货）：v1 只记录，绝不自动接单或下单
                if message["3"]["redReminder"] == "等待买家付款":
                    user_id = message["1"].split("@")[0]
                    user_url = f"https://www.goofish.com/personal?userId={user_id}"
                    logger.info(f"等待买家 {user_url} 付款（人工处理，v1 不自动接单）")
                    return
                elif message["3"]["redReminder"] == "交易关闭":
                    user_id = message["1"].split("@")[0]
                    user_url = f"https://www.goofish.com/personal?userId={user_id}"
                    logger.info(f"买家 {user_url} 交易关闭")
                    return
                elif message["3"]["redReminder"] == "等待卖家发货":
                    user_id = message["1"].split("@")[0]
                    user_url = f"https://www.goofish.com/personal?userId={user_id}"
                    logger.info(f"交易成功 {user_url} 等待卖家发货（人工处理）")
                    return

            except Exception:
                pass

            # 判断消息类型
            if self.is_typing_status(message):
                logger.debug("用户正在输入")
                return
            elif not self.is_chat_message(message):
                logger.debug("其他非聊天消息")
                logger.debug(f"原始消息: {message}")
                return

            # 处理聊天消息
            create_time = int(message["1"]["5"])
            send_user_name = message["1"]["10"]["reminderTitle"]
            send_user_id = message["1"]["10"]["senderUserId"]
            send_message = message["1"]["10"]["reminderContent"]

            # 时效性验证（过滤5分钟前消息）
            if (time.time() * 1000 - create_time) > self.message_expire_time:
                logger.debug("过期消息丢弃")
                return

            # 获取商品ID和会话ID
            url_info = message["1"]["10"]["reminderUrl"]
            item_id = url_info.split("itemId=")[1].split("&")[0] if "itemId=" in url_info else None
            chat_id = message["1"]["2"].split("@")[0]

            if not item_id:
                logger.warning("无法获取商品ID")
                return

            # 检查是否为卖家（自己）发送的控制命令
            if send_user_id == self.myid:
                logger.debug("检测到卖家消息，检查是否为控制命令")

                # 检查切换命令
                if self.check_toggle_keywords(send_message):
                    mode = self.toggle_manual_mode(chat_id)
                    if mode == "manual":
                        logger.info(f"🔴 已接管会话 {chat_id} (商品: {item_id})")
                    else:
                        logger.info(f"🟢 已恢复会话 {chat_id} 的自动回复 (商品: {item_id})")
                    return

                logger.info(f"卖家人工回复 (会话: {chat_id}, 商品: {item_id}): {send_message}")
                return

            logger.info(
                f"用户: {send_user_name} (ID: {send_user_id}), 商品: {item_id}, "
                f"会话: {chat_id}, 消息: {send_message}"
            )

            # 如果当前会话处于人工接管模式，不进行自动回复
            if self.is_manual_mode(chat_id):
                logger.info(f"🔴 会话 {chat_id} 处于人工接管模式，跳过自动回复")
                return
            # 检查是否为带中括号的系统消息
            if self.is_bracket_system_message(send_message):
                logger.info(f"检测到系统消息：'{send_message}'，跳过自动回复")
                return
            if self.is_system_message(message):
                logger.debug("系统消息，跳过处理")
                return

            # 生成回复（ECHO 回显 / BRIDGE 转发 Java），并获知本轮是否转人工
            bot_reply, handoff_requested = await self.compose_reply(send_user_id, item_id, send_message)

            # 无需回复（Java 失败且未配置兜底话术等情形）
            if not bot_reply:
                logger.info(f"[无需回复] 买家 {send_user_name} 的消息未生成回复")
                return

            # X4 双层转人工：Java 侧工单创建成功（handoffRequested=true）时暂停该会话
            # 自动回复，进入人工接管集合；卖家在闲鱼 App 发句号即可恢复自动回复。
            if handoff_requested:
                self.enter_manual_mode(chat_id)
                logger.info(f"🟠 会话 {chat_id} 触发转人工，已暂停自动回复（卖家发句号恢复）")

            # X4 免责话术：每个会话仅首条自动回复追加
            bot_reply = self.apply_disclaimer(chat_id, bot_reply)

            logger.info(f"机器人回复: {bot_reply}")

            # 模拟人工输入延迟
            if self.simulate_human_typing:
                # 基础延迟 0-1秒 + 每字 0.1-0.3秒
                base_delay = random.uniform(0, 1)
                typing_delay = len(bot_reply) * random.uniform(0.1, 0.3)
                total_delay = base_delay + typing_delay
                # 设置最大延迟上限，防止过长回复等待太久
                total_delay = min(total_delay, 10.0)

                logger.info(f"模拟人工输入，延迟发送 {total_delay:.2f} 秒...")
                await asyncio.sleep(total_delay)

            # 出站节流（风控缓解）：补足与该买家上次回复的最小间隔后再发送
            await self.pace_reply(send_user_id)

            await self.send_msg(websocket, chat_id, send_user_id, bot_reply)

        except Exception as e:
            logger.error(f"处理消息时发生错误: {str(e)}")
            logger.debug(f"原始消息: {message_data}")

    async def send_heartbeat(self, ws):
        """发送心跳包并等待响应"""
        try:
            heartbeat_mid = generate_mid()
            heartbeat_msg = {
                "lwp": "/!",
                "headers": {
                    "mid": heartbeat_mid
                }
            }
            await ws.send(json.dumps(heartbeat_msg))
            self.last_heartbeat_time = time.time()
            logger.debug("心跳包已发送")
            return heartbeat_mid
        except Exception as e:
            logger.error(f"发送心跳包失败: {e}")
            raise

    async def heartbeat_loop(self, ws):
        """心跳维护循环"""
        while True:
            try:
                current_time = time.time()

                # 检查是否需要发送心跳
                if current_time - self.last_heartbeat_time >= self.heartbeat_interval:
                    await self.send_heartbeat(ws)

                # 检查上次心跳响应时间，如果超时则认为连接已断开
                if (current_time - self.last_heartbeat_response) > (self.heartbeat_interval + self.heartbeat_timeout):
                    logger.warning("心跳响应超时，可能连接已断开")
                    break

                await asyncio.sleep(1)
            except Exception as e:
                logger.error(f"心跳循环出错: {e}")
                break

    async def handle_heartbeat_response(self, message_data):
        """处理心跳响应"""
        try:
            if (
                isinstance(message_data, dict)
                and "headers" in message_data
                and "mid" in message_data["headers"]
                and "code" in message_data
                and message_data["code"] == 200
            ):
                self.last_heartbeat_response = time.time()
                logger.debug("收到心跳响应")
                return True
        except Exception as e:
            logger.error(f"处理心跳响应出错: {e}")
        return False

    async def main(self):
        while True:
            try:
                # 重置连接重启标志
                self.connection_restart_flag = False

                headers = {
                    "Cookie": self.cookies_str,
                    "Host": "wss-goofish.dingtalk.com",
                    "Connection": "Upgrade",
                    "Pragma": "no-cache",
                    "Cache-Control": "no-cache",
                    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
                    "Origin": "https://www.goofish.com",
                    "Accept-Encoding": "gzip, deflate, br, zstd",
                    "Accept-Language": "zh-CN,zh;q=0.9",
                }

                async with websockets.connect(self.base_url, extra_headers=headers) as websocket:
                    self.ws = websocket
                    await self.init(websocket)

                    # 初始化心跳时间
                    self.last_heartbeat_time = time.time()
                    self.last_heartbeat_response = time.time()

                    # 启动心跳任务
                    self.heartbeat_task = asyncio.create_task(self.heartbeat_loop(websocket))

                    # 启动token刷新任务
                    self.token_refresh_task = asyncio.create_task(self.token_refresh_loop())

                    logger.info(f"侧车已连接（模式: {self.mode}，卖家ID: {self.myid}）")

                    async for message in websocket:
                        try:
                            # 检查是否需要重启连接
                            if self.connection_restart_flag:
                                logger.info("检测到连接重启标志，准备重新建立连接...")
                                break

                            message_data = json.loads(message)

                            # 处理心跳响应
                            if await self.handle_heartbeat_response(message_data):
                                continue

                            # 发送通用ACK响应
                            if "headers" in message_data and "mid" in message_data["headers"]:
                                ack = {
                                    "code": 200,
                                    "headers": {
                                        "mid": message_data["headers"]["mid"],
                                        "sid": message_data["headers"].get("sid", "")
                                    }
                                }
                                # 复制其他可能的header字段
                                for key in ["app-key", "ua", "dt"]:
                                    if key in message_data["headers"]:
                                        ack["headers"][key] = message_data["headers"][key]
                                await websocket.send(json.dumps(ack))

                            # 处理其他消息
                            await self.handle_message(message_data, websocket)

                        except json.JSONDecodeError:
                            logger.error("消息解析失败")
                        except Exception as e:
                            logger.error(f"处理消息时发生错误: {str(e)}")
                            logger.debug(f"原始消息: {message}")

            except websockets.exceptions.ConnectionClosed:
                logger.warning("WebSocket连接已关闭")

            except Exception as e:
                logger.error(f"连接发生错误: {e}")

            finally:
                # 清理任务
                if self.heartbeat_task:
                    self.heartbeat_task.cancel()
                    try:
                        await self.heartbeat_task
                    except asyncio.CancelledError:
                        pass

                if self.token_refresh_task:
                    self.token_refresh_task.cancel()
                    try:
                        await self.token_refresh_task
                    except asyncio.CancelledError:
                        pass

                # 如果是主动重启，立即重连；否则等待5秒
                if self.connection_restart_flag:
                    logger.info("主动重启连接，立即重连...")
                else:
                    logger.info("等待5秒后重连...")
                    await asyncio.sleep(5)


def validate_env(cookies_str):
    """无人值守版配置校验（替代上游交互式 check_and_complete_env，见 PROVENANCE.md）。"""
    if not cookies_str or cookies_str == "your_cookies_here":
        logger.error(
            "COOKIES_STR 未配置：电脑浏览器登录 www.goofish.com → F12 → Network → 刷新 → "
            "任一请求 → Request Headers → 复制完整 cookie 串写入 .env 后重启"
        )
        sys.exit(1)
    if "unb=" not in cookies_str:
        logger.error("COOKIES_STR 缺少 unb= 字段：cookie 不完整或未登录，请重新复制")
        sys.exit(1)


if __name__ == "__main__":
    # 加载环境变量
    if os.path.exists(".env"):
        load_dotenv()
        logger.info("已加载 .env 配置")

    # 配置日志级别
    log_level = os.getenv("LOG_LEVEL", "INFO").upper()
    logger.remove()  # 移除默认handler
    logger.add(
        sys.stderr,
        level=log_level,
        format="<green>{time:YYYY-MM-DD HH:mm:ss.SSS}</green> | <level>{level: <8}</level> | "
               "<cyan>{name}</cyan>:<cyan>{function}</cyan>:<cyan>{line}</cyan> - <level>{message}</level>",
    )
    logger.info(f"日志级别设置为: {log_level}")

    validate_env(os.getenv("COOKIES_STR"))

    xianyu_live = XianyuLive(os.getenv("COOKIES_STR"))
    # 常驻进程
    asyncio.run(xianyu_live.main())
