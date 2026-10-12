package com.smartcs.smart_cs_agent.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.TraceEvent;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MessageReceiptExecutorTest {
    private final MessageReceiptExecutor receipts = new MessageReceiptExecutor(new InMemoryMessageInboxStore(), new ObjectMapper());

    @Test void completedMessageReplaysTheOriginalReplyTimestampAndTraceWithoutRepeatingBusiness() {
        var calls = new AtomicInteger();
        ChatResponse original = receipts.execute("scope", "event", "question", ChatResponse.class, () -> {
            calls.incrementAndGet();
            return new ChatResponse("conv", "buyer", "请卖家核实", List.of(new TraceEvent("handoff", 1, "pause", Map.of("itemId", "123"), 100)), true, 123);
        });
        ChatResponse replay = receipts.execute("scope", "event", "question", ChatResponse.class, () -> { fail("must not re-execute"); return null; });
        assertEquals(1, calls.get());
        assertEquals(original.getReply(), replay.getReply());
        assertEquals(123, replay.getTimestamp());
        assertEquals(100, replay.getTrace().get(0).getTimestamp());
        assertTrue(replay.isHandoffRequested());
    }

    @Test void concurrentDuplicateCannotEnterBusinessWhileFirstExecutionIsPending() throws Exception {
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(() -> receipts.execute("scope", "concurrent", "same", String.class, () -> {
                entered.countDown();
                try { if (!finish.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                return "completed";
            }));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            var pending = assertThrows(MessageReceiptException.class, () -> receipts.execute("scope", "concurrent", "same", String.class, () -> "duplicate"));
            assertEquals("MESSAGE_PROCESSING", pending.getErrorCode());
            finish.countDown(); assertEquals("completed", first.get(1, TimeUnit.SECONDS));
            assertEquals("completed", receipts.execute("scope", "concurrent", "same", String.class, () -> "duplicate"));
        } finally { finish.countDown(); pool.shutdownNow(); }
    }

    @Test void uncertainBusinessResultIsNeverAutomaticallyExecutedAgain() {
        var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> receipts.execute("scope", "uncertain", "same", String.class, () -> {
            calls.incrementAndGet(); throw new IllegalStateException("failure after side effect");
        }));
        for (int attempt = 0; attempt < 3; attempt++) {
            var failure = assertThrows(MessageReceiptException.class, () -> receipts.execute("scope", "uncertain", "same", String.class, () -> "retry"));
            assertEquals("MESSAGE_RESULT_UNKNOWN", failure.getErrorCode());
        }
        assertEquals(1, calls.get());
    }

    @Test void conflictingContentIsRejectedButDifferentAuthenticatedScopesRemainIndependent() {
        String first = MessageReceiptExecutor.scope("chat", "seller-a", "buyer", "conv");
        String second = MessageReceiptExecutor.scope("chat", "seller-b", "buyer", "conv");
        assertEquals("first", receipts.execute(first, "event", "body", String.class, () -> "first"));
        var conflict = assertThrows(MessageReceiptException.class, () -> receipts.execute(first, "event", "changed", String.class, () -> "wrong"));
        assertEquals("MESSAGE_PAYLOAD_CONFLICT", conflict.getErrorCode());
        assertEquals("second", receipts.execute(second, "event", "changed", String.class, () -> "second"));
        assertNotEquals(MessageReceiptExecutor.scope("a", "bc"), MessageReceiptExecutor.scope("ab", "c"));
    }
}
