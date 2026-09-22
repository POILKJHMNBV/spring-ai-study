package org.example.ai.harness;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.time.format.DateTimeFormatter;
import static org.junit.jupiter.api.Assertions.*;

/** 限流等待边界：不得因非法或超长头部导致提前重试。 */
class ToolFailureTest {
    @Test
    void retryAfterSupportsSecondsAndHttpDate() {
        Instant now = Instant.parse("2026-09-21T00:00:00Z");
        assertEquals(Duration.ofSeconds(2), ToolFailure.retryAfter("2", now));
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(2).atZone(ZoneOffset.UTC));
        assertEquals(Duration.ofSeconds(2), ToolFailure.retryAfter(date, now));
        assertEquals(Duration.ZERO, ToolFailure.retryAfter(date, now.plusSeconds(3)));
        for (String invalid : new String[]{"-1", "nonsense", "4", "999999999999999999999"}) {
            assertNull(ToolFailure.retryAfter(invalid, now));
        }
        assertNull(ToolFailure.retryAfter(null, now));
    }
}
