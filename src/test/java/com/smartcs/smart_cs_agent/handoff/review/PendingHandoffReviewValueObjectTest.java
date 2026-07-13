package com.smartcs.smart_cs_agent.handoff.review;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 人工复核分页值对象的边界测试。
 *
 * <p>这些约束看似简单，但它们是 Store 和后续 HTTP 游标编解码之间共同遵守的契约。
 * 单独测试后，即使未来替换 Controller 或数据库，也不会悄悄接受无效游标。</p>
 */
class PendingHandoffReviewValueObjectTest {

    @Test
    void shouldRejectInvalidCursorCoordinatesAndNormalizeOutboxId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PendingHandoffReviewCursor(-1, "outbox-1")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new PendingHandoffReviewCursor(0, null)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new PendingHandoffReviewCursor(0, "   ")
        );

        PendingHandoffReviewCursor cursor = new PendingHandoffReviewCursor(0, "  outbox-1  ");
        assertEquals("outbox-1", cursor.outboxId());
    }

    @Test
    void shouldKeepPageCursorAndHasMoreConsistent() {
        PendingHandoffReviewCursor cursor = new PendingHandoffReviewCursor(10, "outbox-1");

        assertThrows(
                IllegalArgumentException.class,
                () -> new PendingHandoffReviewPage(List.of(), null, true)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new PendingHandoffReviewPage(List.of(), cursor, false)
        );

        // records=null 会被安全地归一化为空列表，调用方无需重复判空。
        PendingHandoffReviewPage emptyPage = new PendingHandoffReviewPage(null, null, false);
        assertTrue(emptyPage.records().isEmpty());
    }
}
