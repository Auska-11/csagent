"""Offline regressions for batches, replay, bounded workers, takeover and uncertain socket writes."""
import asyncio
import base64
import hashlib
import json
import os
import sys
import tempfile
import time
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import AsyncMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from main import XianyuLive
from protocol import MessageEvent, parse_chat_event
from delivery import MessageJournal, MessageDispatcher, InboxFullError


def event(event_id="event", chat="chat", text="还在吗", kind="buyer"):
    return MessageEvent(event_id, chat, "66001" if kind == "buyer" else "12345", "90111", text, int(time.time() * 1000), kind)


def message(text="还在吗", sender="66001", stamp=None, chat="70001"):
    return {"1": {"2": chat + "@goofish", "5": stamp or int(time.time() * 1000), "10": {
        "reminderContent": text, "senderUserId": sender,
        "reminderUrl": "https://www.goofish.com/item?itemId=90111&from=chat"}}}


def batch(children, mid="batch"):
    records = [{"data": base64.b64encode(json.dumps(child).encode()).decode()} for child in children]
    return {"headers": {"mid": mid, "sid": "session"}, "body": {"syncPushPackage": {"data": records}}}


class JournalTests(unittest.TestCase):
    def test_restart_reprocesses_http_with_same_event_but_never_retries_ambiguous_send(self):
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "messages.db")
            journal = MessageJournal(path)
            journal.accept(event("http")); journal.accept(event("socket", chat="other"))
            journal.set_status("http", "PROCESSING"); journal.set_status("socket", "SENDING")
            journal.close()
            restarted = MessageJournal(path)
            try:
                self.assertEqual(restarted.summary(), {"RECEIVED": 1, "UNKNOWN": 1})
                self.assertFalse(restarted.accept(event("http")))
                self.assertFalse(restarted.accept(event("socket", chat="other")))
            finally: restarted.close()

    def test_bounded_admission_reserves_capacity_for_seller_control(self):
        journal = MessageJournal(capacity=32)
        try:
            for number in range(16): journal.accept(event(str(number)))
            with self.assertRaises(InboxFullError): journal.accept(event("overflow"))
            self.assertTrue(journal.accept(event("pause", kind="toggle")))
        finally: journal.close()

    def test_own_seller_echo_is_excluded_without_deduplicating_buyer_text(self):
        journal = MessageJournal()
        try:
            outgoing = event("outgoing", text="你好"); journal.accept(outgoing)
            journal.record_outbound(outgoing.event_id, "你好")
            self.assertTrue(journal.is_own_echo(event("echo", text="你好", kind="seller")))
            self.assertFalse(journal.is_own_echo(event("buyer", text="你好")))
            self.assertFalse(journal.is_own_echo(event("another-chat", chat="another", text="你好", kind="seller")))
            self.assertTrue(journal.accept(event("buyer-1", text="你好")))
            self.assertTrue(journal.accept(event("buyer-2", text="你好")))
        finally: journal.close()


class ReceiveTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        with patch.dict(os.environ, {"SIDECAR_MODE": "ECHO"}): self.live = XianyuLive("unb=12345; cookie2=offline")
        self.addCleanup(self.live.journal.close)
        self.ws = AsyncMock()

    async def test_entire_batch_is_admitted_once_and_changed_carrier_mid_does_not_change_event_identity(self):
        first = message(stamp=int(time.time() * 1000))
        second = message(text="配件有吗", chat="70002")
        await self.live.handle_message(batch([first, second]), self.ws)
        await self.live.handle_message(batch([first, second], "reconnected-carrier"), self.ws)
        self.assertEqual(self.live.journal.summary(), {"RECEIVED": 2})
        self.assertEqual(self.ws.send.await_count, 2)
        self.assertEqual(parse_chat_event(first, "12345", 300000).item_id, "90111")

    async def test_damaged_child_does_not_discard_other_valid_children(self):
        packet = batch([message(), message(text="第二条", chat="70002")])
        packet["body"]["syncPushPackage"]["data"].insert(1, {"data": "invalid-ciphertext"})
        await self.live.handle_message(packet, self.ws)
        self.assertEqual(self.live.journal.summary(), {"RECEIVED": 2})

    async def test_receiving_does_not_wait_for_model_and_duplicate_pause_command_does_not_resume(self):
        self.live.compose_reply = AsyncMock(side_effect=AssertionError("receive loop must not execute model"))
        packet = batch([message(), message("。", sender="12345")])
        await asyncio.wait_for(self.live.handle_message(packet, self.ws), 0.5)
        self.assertTrue(self.live.is_manual_mode("70001"))
        await self.live.handle_message(packet, self.ws)
        self.assertTrue(self.live.is_manual_mode("70001"))
        self.live.compose_reply.assert_not_awaited()

    async def test_inbox_full_is_not_acknowledged_as_accepted(self):
        self.live.journal.capacity = 32
        for number in range(16): self.live.journal.accept(event(str(number)))
        with self.assertRaises(InboxFullError): await self.live.handle_message(batch([message()]), self.ws)
        self.ws.send.assert_not_awaited()

    async def test_actual_sender_suppresses_reply_when_seller_takes_over_during_pacing(self):
        incoming = event(chat="70001"); self.live.journal.accept(incoming)
        self.live.journal.set_status(incoming.event_id, "READY", json.dumps({"reply": "旧回复"}))
        self.live._connected.set(); self.live.ws = self.ws
        waiting = asyncio.Event(); release = asyncio.Event()
        async def pace(buyer): waiting.set(); await release.wait()
        self.live.pace_reply = pace
        sending = asyncio.create_task(self.live._deliver_event(incoming, {"reply": "旧回复"}, lambda: False))
        await asyncio.wait_for(waiting.wait(), 1)
        self.live.enter_manual_mode("70001"); release.set(); await sending
        self.ws.send.assert_not_awaited()
        self.assertEqual(self.live.journal.summary(), {"CANCELLED": 1})


class DispatcherTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.journal = MessageJournal(lanes=2)
        self.dispatcher = None

    async def asyncTearDown(self):
        if self.dispatcher: await self.dispatcher.close()
        self.journal.close()

    async def until(self, predicate):
        async def check():
            while not predicate(): await asyncio.sleep(0.005)
        await asyncio.wait_for(check(), 1)

    def start(self, process, deliver, paused=lambda chat: False):
        self.dispatcher = MessageDispatcher(self.journal, process, deliver, paused)
        self.dispatcher.start()

    async def test_same_chat_is_ordered_other_chat_and_seller_control_continue_during_slow_model(self):
        entered = asyncio.Event(); release = asyncio.Event(); paused = set(); sent = []
        lane = lambda chat: int(hashlib.sha256(chat.encode()).hexdigest(), 16) % 2
        other = next(str(number) for number in range(100) if lane(str(number)) != lane("slow"))
        async def process(incoming):
            if incoming.kind == "toggle": paused.add(incoming.chat_id); return {}
            if incoming.event_id == "first": entered.set(); await release.wait()
            return {"reply": incoming.event_id}
        async def deliver(incoming, response, superseded):
            sent.append(incoming.event_id); self.journal.set_status(incoming.event_id, "SENT")
        self.journal.accept(event("first", chat="slow")); self.journal.accept(event("second", chat="slow"))
        self.start(process, deliver, lambda chat: chat in paused)
        await asyncio.wait_for(entered.wait(), 1)
        self.journal.accept(event("other", chat=other)); self.journal.accept(event("pause", chat="slow", kind="toggle"))
        await self.until(lambda: "other" in sent and "slow" in paused)
        self.assertNotIn("second", sent)
        release.set()
        await self.until(lambda: self.journal.summary().get("CANCELLED") == 2)
        self.assertEqual(sent, ["other"])

    async def test_ready_reply_survives_restart_without_reexecuting_business_and_handoff_reply_can_send(self):
        incoming = event(); self.journal.accept(incoming)
        self.journal.set_status(incoming.event_id, "READY", json.dumps({"reply": "已转卖家", "handoffRequested": True}))
        process = AsyncMock(side_effect=AssertionError("business must not re-execute"))
        async def deliver(incoming, response, superseded): self.journal.set_status(incoming.event_id, "SENT")
        self.start(process, deliver, lambda chat: True)
        await self.until(lambda: self.journal.summary().get("SENT") == 1)
        process.assert_not_awaited()

    async def test_failed_socket_write_is_unknown_and_is_not_automatically_sent_again(self):
        process = AsyncMock(return_value={"reply": "你好"}); writes = []
        async def deliver(incoming, response, superseded):
            self.journal.set_status(incoming.event_id, "SENDING"); writes.append(incoming.event_id)
            raise ConnectionError("socket closed after possible delivery")
        self.journal.accept(event()); self.start(process, deliver)
        await self.until(lambda: self.journal.summary().get("UNKNOWN") == 1)
        await asyncio.sleep(0.1)
        self.assertEqual(writes, ["event"]); self.assertEqual(process.await_count, 1)

    async def test_delayed_sending_rechecks_takeover_after_waits(self):
        incoming = event(); self.journal.accept(incoming)
        self.journal.set_status(incoming.event_id, "READY", json.dumps({"reply": "旧回复"}))
        waiting = asyncio.Event(); release = asyncio.Event(); sent = []
        async def deliver(incoming, response, superseded):
            waiting.set(); await release.wait()
            if superseded(): self.journal.set_status(incoming.event_id, "CANCELLED")
            else: sent.append(incoming.event_id)
        self.start(AsyncMock(return_value={}), deliver)
        await asyncio.wait_for(waiting.wait(), 1)
        self.journal.accept(event("pause", kind="toggle")); release.set()
        await self.until(lambda: self.journal.summary().get("CANCELLED") == 1)
        self.assertEqual(sent, [])
