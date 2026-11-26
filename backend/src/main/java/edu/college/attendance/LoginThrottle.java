package edu.college.attendance;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Slows password guessing: after {@link #MAX_FAILURES} wrong passwords for one email within
 * {@link #WINDOW}, further attempts for that email are refused until the window passes.
 * State is in memory, so a restart clears it; that is acceptable for a single campus server.
 */
@Component
public class LoginThrottle {
    static final int MAX_FAILURES = 8;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private record Failures(int count, Instant firstAt) {}
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginThrottle() { this(Clock.systemUTC()); }
    LoginThrottle(Clock clock) { this.clock = clock; }

    public boolean isBlocked(String email) {
        Failures f = failures.get(email);
        if (f == null) return false;
        if (expired(f)) { failures.remove(email, f); return false; }
        return f.count() >= MAX_FAILURES;
    }

    public void recordFailure(String email) {
        if (failures.size() > 10_000) failures.values().removeIf(this::expired);
        failures.compute(email, (k, f) -> f == null || expired(f) ? new Failures(1, clock.instant()) : new Failures(f.count() + 1, f.firstAt()));
    }

    public void recordSuccess(String email) { failures.remove(email); }

    private boolean expired(Failures f) { return f.firstAt().plus(WINDOW).isBefore(clock.instant()); }
}
