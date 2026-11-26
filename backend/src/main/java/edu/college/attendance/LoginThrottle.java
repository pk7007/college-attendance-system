package edu.college.attendance;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Slows password guessing: after {@link #MAX_FAILURES} wrong passwords for one email from one
 * client address within {@link #WINDOW}, further attempts from that address are refused until the
 * window passes. Keying by address as well as email means a stranger elsewhere on the network
 * cannot lock a teacher out of their own account. State is in memory (a restart clears it) and is
 * capped so a flood of distinct emails cannot exhaust memory.
 */
@Component
public class LoginThrottle {
    static final int MAX_FAILURES = 8;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final int MAX_TRACKED = 10_000;

    private record Failures(int count, Instant firstAt) {}
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginThrottle() { this(Clock.systemUTC()); }
    LoginThrottle(Clock clock) { this.clock = clock; }

    static String key(String email, String clientAddress) { return email + '|' + clientAddress; }

    public boolean isBlocked(String key) {
        Failures f = failures.get(key);
        if (f == null) return false;
        if (expired(f)) { failures.remove(key, f); return false; }
        return f.count() >= MAX_FAILURES;
    }

    public void recordFailure(String key) {
        if (failures.size() >= MAX_TRACKED) {
            failures.values().removeIf(this::expired);
            if (failures.size() >= MAX_TRACKED) failures.clear(); // under attack: forget rather than grow without bound
        }
        failures.compute(key, (k, f) -> f == null || expired(f) ? new Failures(1, clock.instant()) : new Failures(f.count() + 1, f.firstAt()));
    }

    public void recordSuccess(String key) { failures.remove(key); }

    int tracked() { return failures.size(); }

    private boolean expired(Failures f) { return !f.firstAt().plus(WINDOW).isAfter(clock.instant()); }
}
