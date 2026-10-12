"""A bounded, durable inbox and fixed workers. Platform send uncertainty is never retried blindly."""

import asyncio
import hashlib
import json
import os
import sqlite3
import time
from pathlib import Path

from loguru import logger
from client import MessagePendingError, MessageResultUnknownError
from protocol import MessageEvent


class InboxFullError(Exception):
    pass


class MessageJournal:
    def __init__(self, path=":memory:", capacity=256, lanes=4):
        if capacity < 32 or lanes < 1 or lanes > 16:
            raise ValueError("queue capacity must be >=32 and workers must be 1..16")
        self.capacity = capacity
        self.lanes = lanes
        if path != ":memory:":
            Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(path)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("""CREATE TABLE IF NOT EXISTS messages (
            seq INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT NOT NULL UNIQUE,
            chat_id TEXT NOT NULL, kind TEXT NOT NULL, lane INTEGER NOT NULL,
            payload TEXT NOT NULL, status TEXT NOT NULL, response TEXT,
            attempts INTEGER NOT NULL DEFAULT 0, available_at REAL NOT NULL DEFAULT 0,
            updated_at REAL NOT NULL, error TEXT)""")
        columns = {row[1] for row in self.db.execute("PRAGMA table_info(messages)")}
        if "outbound_text" not in columns:
            self.db.execute("ALTER TABLE messages ADD COLUMN outbound_text TEXT")
        if "outbound_at" not in columns:
            self.db.execute("ALTER TABLE messages ADD COLUMN outbound_at REAL")
        self.db.execute("CREATE INDEX IF NOT EXISTS ready_messages ON messages(lane, status, seq)")
        # HTTP processing is recoverable using Java receipts; a socket write is ambiguous.
        self.db.execute("UPDATE messages SET status='RECEIVED' WHERE status='PROCESSING'")
        self.db.execute("UPDATE messages SET status='UNKNOWN', error='SEND_INTERRUPTED' WHERE status='SENDING'")
        self.db.commit()
        if path != ":memory:":
            os.chmod(path, 0o600)

    def accept(self, event):
        if self.db.execute("SELECT 1 FROM messages WHERE event_id=?", (event.event_id,)).fetchone():
            return False
        pending = self.db.execute("SELECT COUNT(*) FROM messages WHERE status IN ('RECEIVED','PROCESSING','READY','SENDING')").fetchone()[0]
        # Keep a small reserved space for seller controls under buyer load.
        limit = self.capacity if event.kind != "buyer" else max(1, self.capacity - 16)
        if pending >= limit:
            raise InboxFullError("sidecar inbox is full")
        lane = -1 if event.kind != "buyer" else int(hashlib.sha256(event.chat_id.encode()).hexdigest(), 16) % self.lanes
        self.db.execute("""INSERT INTO messages(event_id,chat_id,kind,lane,payload,status,updated_at)
            VALUES(?,?,?,?,?,'RECEIVED',?)""", (event.event_id, event.chat_id, event.kind, lane,
                                               json.dumps(event.to_dict(), ensure_ascii=False), time.time()))
        # Old completed events have already passed the ingress expiry window. Unknown sends remain for review.
        self.db.execute("DELETE FROM messages WHERE status IN ('SENT','CANCELLED') AND updated_at<?", (time.time() - 86400,))
        self.db.commit()
        return True

    def claim(self, lane):
        # An earlier waiting event blocks later messages of the same conversation; other chats continue.
        row = self.db.execute("""SELECT m.* FROM messages m
            WHERE m.lane=? AND m.status IN ('RECEIVED','READY') AND m.available_at<=?
            AND NOT EXISTS (SELECT 1 FROM messages p WHERE p.chat_id=m.chat_id AND p.seq<m.seq
                AND p.status IN ('RECEIVED','PROCESSING','READY','SENDING')
                AND ((m.kind='buyer' AND p.kind='buyer') OR (m.kind!='buyer' AND p.kind!='buyer')))
            ORDER BY m.seq LIMIT 1""", (lane, time.time())).fetchone()
        if row:
            self.set_status(row["event_id"], "PROCESSING" if row["status"] == "RECEIVED" else "READY")
        return row

    def newer_control(self, row):
        return self.db.execute("SELECT 1 FROM messages WHERE chat_id=? AND kind!='buyer' AND seq>? LIMIT 1",
                               (row["chat_id"], row["seq"])).fetchone() is not None

    def set_status(self, event_id, status, response=None, error=None, retry_in=0):
        self.db.execute("""UPDATE messages SET status=?, response=COALESCE(?,response), error=?,
            available_at=?, updated_at=?, attempts=attempts+? WHERE event_id=?""",
                        (status, response, error, time.time() + retry_in, time.time(), int(bool(retry_in)), event_id))
        self.db.commit()

    def record_outbound(self, event_id, text):
        self.db.execute("UPDATE messages SET outbound_text=?, outbound_at=? WHERE event_id=?",
                        (text, time.time() * 1000, event_id))
        self.db.commit()

    def is_own_echo(self, event):
        # Only our own seller message, full exact text, same chat and a narrow send-time window.
        # Buyer events always retain their independent platform event identity, even for repeated text.
        if event.kind != "seller":
            return False
        return self.db.execute("""SELECT 1 FROM messages WHERE chat_id=? AND outbound_text=?
            AND outbound_at<=? AND outbound_at>=? LIMIT 1""",
            (event.chat_id, event.text, event.created_at + 2000, event.created_at - 120000)).fetchone() is not None

    def summary(self):
        return dict(self.db.execute("SELECT status,COUNT(*) FROM messages GROUP BY status").fetchall())

    def close(self):
        self.db.close()


class MessageDispatcher:
    def __init__(self, journal, process, deliver, paused, expire_ms=300000):
        self.journal = journal
        self.process = process
        self.deliver = deliver
        self.paused = paused
        self.expire_ms = expire_ms
        self.tasks = []

    def start(self):
        if not self.tasks:
            self.tasks = [asyncio.create_task(self._worker(lane)) for lane in range(-1, self.journal.lanes)]

    async def close(self):
        for task in self.tasks:
            task.cancel()
        await asyncio.gather(*self.tasks, return_exceptions=True)
        self.tasks = []

    async def _worker(self, lane):
        while True:
            row = self.journal.claim(lane)
            if row is None:
                await asyncio.sleep(0.05)
                continue
            event = MessageEvent(**json.loads(row["payload"]))
            try:
                cached = json.loads(row["response"]) if row["response"] else None
                if event.kind == "buyer" and self._superseded(row, event, cached):
                    self.journal.set_status(event.event_id, "CANCELLED", error="SELLER_TAKEOVER_OR_EXPIRED")
                    continue
                response = cached if cached is not None else await self.process(event)
                if event.kind != "buyer" or not response.get("reply"):
                    self.journal.set_status(event.event_id, "SENT")
                    continue
                self.journal.set_status(event.event_id, "READY", json.dumps(response, ensure_ascii=False))
                # Only a later human action cancels a handoff's initial buyer-facing reply.
                if self.journal.newer_control(row) or (self.paused(event.chat_id) and not response.get("handoffRequested")):
                    self.journal.set_status(event.event_id, "CANCELLED", error="SELLER_TAKEOVER")
                    continue
                await self.deliver(event, response, lambda: self.journal.newer_control(row))
            except MessageResultUnknownError:
                self.journal.set_status(event.event_id, "UNKNOWN", error="JAVA_RESULT_UNKNOWN")
            except asyncio.CancelledError:
                # Recovery rules run when the journal opens again; never turn SENDING into RECEIVED.
                raise
            except Exception as failure:
                current = self.journal.db.execute("SELECT status FROM messages WHERE event_id=?", (event.event_id,)).fetchone()[0]
                if current == "SENDING":
                    self.journal.set_status(event.event_id, "UNKNOWN", error="SEND_RESULT_UNKNOWN")
                elif row["attempts"] >= 4:
                    self.journal.set_status(event.event_id, "UNKNOWN", error="PROCESSING_REQUIRES_REVIEW")
                else:
                    # The process callback must propagate uncertainty; Java receives the same event id.
                    state = "READY" if current == "READY" else "RECEIVED"
                    self.journal.set_status(event.event_id, state, error=type(failure).__name__,
                                            retry_in=min(2 ** row["attempts"], 16))
                logger.warning("消息处理未完成: event={} exceptionType={}", event.event_id[:12], type(failure).__name__)

    def _superseded(self, row, event, response=None):
        own_handoff = response is not None and response.get("handoffRequested")
        return ((self.paused(event.chat_id) and not own_handoff) or self.journal.newer_control(row)
                or time.time() * 1000 - event.created_at > self.expire_ms)
