package edu.college.attendance;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Unit tests that run on every `mvnw verify`, with no database needed. */
class SecurityRulesTest {
    /** A clock the test can move forward. */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-26T09:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test void blocksAfterTooManyFailuresAndForgetsAfterTheWindow() {
        TestClock clock = new TestClock();
        LoginThrottle throttle = new LoginThrottle(clock);
        String key = LoginThrottle.key("teacher@college.test", "10.0.0.5");
        for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) throttle.recordFailure(key);
        assertFalse(throttle.isBlocked(key), "one attempt left before blocking");
        throttle.recordFailure(key);
        assertTrue(throttle.isBlocked(key));

        clock.now = clock.now.plus(LoginThrottle.WINDOW).minusSeconds(1);
        assertTrue(throttle.isBlocked(key), "still blocked inside the window");
        clock.now = clock.now.plus(Duration.ofSeconds(1));
        assertFalse(throttle.isBlocked(key), "unblocked once the window has passed");
    }

    @Test void anotherAddressAndASuccessfulLoginAreNotAffected() {
        LoginThrottle throttle = new LoginThrottle(new TestClock());
        String attacker = LoginThrottle.key("teacher@college.test", "10.0.0.66");
        String teacher = LoginThrottle.key("teacher@college.test", "10.0.0.5");
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) throttle.recordFailure(attacker);
        assertTrue(throttle.isBlocked(attacker));
        assertFalse(throttle.isBlocked(teacher), "a stranger cannot lock the teacher out from another computer");

        throttle.recordFailure(teacher);
        throttle.recordSuccess(teacher);
        for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) throttle.recordFailure(teacher);
        assertFalse(throttle.isBlocked(teacher), "a successful sign-in resets the count");
    }

    @Test void memoryStaysBoundedUnderAFloodOfEmails() {
        LoginThrottle throttle = new LoginThrottle(new TestClock());
        for (int i = 0; i < LoginThrottle.MAX_TRACKED * 2; i++) throttle.recordFailure(LoginThrottle.key("user" + i + "@x.test", "10.0.0.9"));
        assertTrue(throttle.tracked() <= LoginThrottle.MAX_TRACKED);
    }

    @Test void csvCellsAreQuotedAndFormulasNeutralised() {
        assertEquals("\"Sam Patel\"", ReportsController.cell("Sam Patel"));
        assertEquals("\"Say \"\"hi\"\"\"", ReportsController.cell("Say \"hi\""));
        assertEquals("\"\"", ReportsController.cell(null));
        for (String formula : new String[]{"=HYPERLINK(\"x\")", "+1", "-2+3", "@SUM(A1)", "\tx", "=1\n=2"})
            assertTrue(ReportsController.cell(formula).startsWith("\"'"), "formula is prefixed with a quote: " + formula);
    }
}
