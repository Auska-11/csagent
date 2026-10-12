"""Decode platform batches into immutable events. No HTTP, persistence or reply generation."""

import base64
import hashlib
import json
import time
from dataclasses import asdict, dataclass
from urllib.parse import parse_qs, urlparse

from loguru import logger
from utils.xianyu_utils import decrypt


@dataclass(frozen=True)
class MessageEvent:
    event_id: str
    chat_id: str
    sender_id: str
    item_id: str
    text: str
    created_at: int
    kind: str = "buyer"
    desired_manual: bool | None = None

    def to_dict(self):
        return asdict(self)


def decode_sync_messages(frame):
    records = frame.get("body", {}).get("syncPushPackage", {}).get("data", [])
    if not isinstance(records, list):
        return
    for record in records:
        if not isinstance(record, dict) or not record.get("data"):
            continue
        raw = record["data"]
        try:
            try:
                message = json.loads(base64.b64decode(raw).decode("utf-8"))
            except (ValueError, UnicodeError):
                message = json.loads(decrypt(raw))
            if isinstance(message, dict):
                yield message
        except Exception as failure:
            # One damaged child must not discard the rest of the batch, nor log decrypted data.
            logger.warning("消息解码失败: exceptionType={}", type(failure).__name__)


def parse_chat_event(message, seller_id, expire_ms):
    body = message.get("1")
    if not isinstance(body, dict) or not isinstance(body.get("10"), dict):
        return None
    details = body["10"]
    if not isinstance(details.get("reminderContent"), str):
        return None
    try:
        created_at = int(body["5"])
        if time.time() * 1000 - created_at > expire_ms:
            return None
        item_id = parse_qs(urlparse(details["reminderUrl"]).query).get("itemId", [""])[0]
        chat_id = str(body["2"]).split("@")[0]
        sender_id = str(details["senderUserId"])
        if not item_id.isdigit() or not sender_id.isdigit() or not chat_id:
            return None
    except (KeyError, TypeError, ValueError):
        return None
    # The carrier mid identifies a batch, not a buyer message. Hash the decrypted business
    # event including its platform timestamp/coordinates, so re-enveloping does not change identity.
    canonical = json.dumps([str(seller_id), message], sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    event_id = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
    return MessageEvent(event_id, chat_id, sender_id, item_id,
                        details["reminderContent"], created_at,
                        "seller" if sender_id == str(seller_id) else "buyer")
