package edu.college.attendance;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Shared lookups used by every controller so all roles see the same rules. */
@Component
public class AppSupport {
    /**
     * Joins and filter that define a "live" class: the section, its course, department,
     * semester and the subject must all be active. Admin, faculty and report views use this
     * same condition so a deactivation is reflected consistently for every user.
     * Expects aliases s (sections) and sub (subjects) to be joined by the caller.
     */
    public static final String LIVE_CLASS_JOINS =
        " JOIN courses c ON c.id=s.course_id" +
        " JOIN departments d ON d.id=c.department_id" +
        " JOIN semesters se ON se.id=s.semester_id";
    public static final String LIVE_CLASS_FILTER =
        " s.active=TRUE AND c.active=TRUE AND d.active=TRUE AND se.active=TRUE AND sub.active=TRUE AND sub.course_id=s.course_id";

    private final JdbcTemplate db;
    public AppSupport(JdbcTemplate db) { this.db = db; }

    /** Database id of the signed-in, active user. */
    public long userId(Authentication auth) {
        List<Long> ids = db.query("SELECT id FROM users WHERE email=? AND active=TRUE", (rs, n) -> rs.getLong(1), auth.getName());
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in again.");
        return ids.getFirst();
    }

    public void audit(Authentication auth, String action, String entityType, long entityId) {
        db.update("INSERT INTO audit_events(actor_user_id,action,entity_type,entity_id) VALUES((SELECT id FROM users WHERE email=?),?,?,?)",
            auth.getName(), action, entityType, entityId);
    }

    /** Parses an optional yyyy-MM-dd query parameter with a clear error message. */
    public static LocalDate optionalDate(String value, String name) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDate.parse(value.trim()); }
        catch (DateTimeParseException e) { throw new IllegalArgumentException(name + " must be a date in YYYY-MM-DD format."); }
    }

    public static void checkRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("From date must be on or before To date.");
    }
}
