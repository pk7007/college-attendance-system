package edu.college.attendance;

import static edu.college.attendance.AppSupport.LIVE_CLASS_FILTER;
import static edu.college.attendance.AppSupport.LIVE_CLASS_JOINS;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/faculty")
@PreAuthorize("hasRole('FACULTY')")
public class FacultyController {
    private final JdbcTemplate db;
    private final AppSupport app;
    public FacultyController(JdbcTemplate db, AppSupport app) { this.db = db; this.app = app; }

    public record NewSession(@NotNull LocalDate sessionDate, @NotBlank @Size(max = 80) String sessionLabel) {}
    public record Mark(long studentId, @NotBlank String status) {}
    public record Attendance(@NotNull Long expectedVersion, @NotEmpty List<@Valid Mark> records) {}

    /** A faculty assignment is usable only while the assignment and the whole class are active. */
    private static final String LIVE_ASSIGNMENT =
        " FROM faculty_assignments a JOIN sections s ON s.id=a.section_id JOIN subjects sub ON sub.id=a.subject_id" + LIVE_CLASS_JOINS +
        " WHERE a.active=TRUE AND" + LIVE_CLASS_FILTER;

    private record Assignment(long sectionId, long subjectId, LocalDate semesterStart, LocalDate semesterEnd) {}

    @GetMapping("/classes")
    List<Map<String, Object>> classes(Authentication auth) {
        return db.queryForList(
            "SELECT a.id assignmentId,s.id sectionId,sub.id subjectId,s.code sectionCode,c.name courseName,sub.code subjectCode,sub.name subjectName,se.label semester," +
            " se.start_date semesterStart,se.end_date semesterEnd," +
            " (SELECT COUNT(*) FROM student_enrollments e JOIN users u ON u.id=e.student_user_id WHERE e.section_id=s.id AND e.status='ACTIVE' AND u.active=TRUE) students," +
            " (SELECT COUNT(*) FROM class_sessions cs WHERE cs.section_id=s.id AND cs.subject_id=sub.id AND cs.state='COMPLETED') completedSessions" +
            LIVE_ASSIGNMENT + " AND a.faculty_user_id=? ORDER BY c.name,s.code,sub.name", app.userId(auth));
    }

    @GetMapping("/classes/{assignmentId}/roster")
    List<Map<String, Object>> roster(@PathVariable long assignmentId, Authentication auth) {
        return db.queryForList(
            "SELECT u.id studentId,u.display_name displayName,u.email FROM student_enrollments e JOIN users u ON u.id=e.student_user_id" +
            " WHERE e.section_id=? AND e.status='ACTIVE' AND u.active=TRUE ORDER BY u.display_name,u.id",
            assignment(assignmentId, app.userId(auth)).sectionId());
    }

    @GetMapping("/classes/{assignmentId}/sessions")
    List<Map<String, Object>> sessions(@PathVariable long assignmentId, Authentication auth) {
        Assignment a = assignment(assignmentId, app.userId(auth));
        return db.queryForList("SELECT id,session_date sessionDate,session_label sessionLabel,state,version FROM class_sessions WHERE section_id=? AND subject_id=? ORDER BY session_date DESC,id DESC",
            a.sectionId(), a.subjectId());
    }

    /** Opens a session, returns the existing one for the same date/label, or reopens it if it was cancelled. */
    @PostMapping("/classes/{assignmentId}/sessions") @Transactional
    Map<String, Object> createSession(@PathVariable long assignmentId, @Valid @RequestBody NewSession input, Authentication auth) {
        long uid = app.userId(auth);
        Assignment a = assignment(assignmentId, uid);
        LocalDate date = input.sessionDate();
        String label = input.sessionLabel().trim();
        if (date.isBefore(a.semesterStart()) || date.isAfter(a.semesterEnd()))
            throw new IllegalArgumentException("Session date must be within the semester (" + a.semesterStart() + " to " + a.semesterEnd() + ").");
        // One day of tolerance so a browser in a different timezone can still open "today".
        if (date.isAfter(LocalDate.now().plusDays(1)))
            throw new IllegalArgumentException("Attendance cannot be taken for a future date.");

        List<Map<String, Object>> found = db.queryForList("SELECT id,state FROM class_sessions WHERE section_id=? AND subject_id=? AND session_date=? AND session_label=? FOR UPDATE",
            a.sectionId(), a.subjectId(), date, label);
        if (!found.isEmpty()) {
            long id = ((Number) found.getFirst().get("id")).longValue();
            if ("CANCELLED".equals(found.getFirst().get("state"))) {
                db.update("UPDATE class_sessions SET state='OPEN',version=version+1 WHERE id=?", id);
                app.audit(auth, "SESSION_REOPENED", "CLASS_SESSION", id);
                return Map.of("id", id, "status", "reopened");
            }
            return Map.of("id", id, "status", "existing");
        }
        db.update("INSERT INTO class_sessions(section_id,subject_id,session_date,session_label,created_by) VALUES(?,?,?,?,?)",
            a.sectionId(), a.subjectId(), date, label, uid);
        Long id = db.queryForObject("SELECT id FROM class_sessions WHERE section_id=? AND subject_id=? AND session_date=? AND session_label=?",
            Long.class, a.sectionId(), a.subjectId(), date, label);
        app.audit(auth, "SESSION_OPENED", "CLASS_SESSION", id);
        return Map.of("id", id, "status", "created");
    }

    @GetMapping("/sessions/{sessionId}")
    Map<String, Object> session(@PathVariable long sessionId, Authentication auth) {
        Map<String, Object> s = sessionForFaculty(sessionId, app.userId(auth));
        s.put("attendance", db.queryForList("SELECT student_user_id studentId,status FROM attendance_records WHERE class_session_id=? ORDER BY student_user_id", sessionId));
        return s;
    }

    @PutMapping("/sessions/{sessionId}/attendance") @Transactional
    Map<String, Object> save(@PathVariable long sessionId, @Valid @RequestBody Attendance input, Authentication auth) {
        long uid = app.userId(auth);
        Map<String, Object> session = sessionForFaculty(sessionId, uid);
        // Lock the session row so concurrent saves by two faculty members are serialised.
        Map<String, Object> current = db.queryForMap("SELECT state,version FROM class_sessions WHERE id=? FOR UPDATE", sessionId);
        if ("CANCELLED".equals(current.get("state")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This session was cancelled. Open it again with the same date and label to take attendance.");
        long version = ((Number) current.get("version")).longValue();
        if (version != input.expectedVersion())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Someone else saved this session. Reload it and review the current marks.");

        long section = ((Number) session.get("sectionId")).longValue();
        List<Long> roster = db.query("SELECT e.student_user_id FROM student_enrollments e JOIN users u ON u.id=e.student_user_id WHERE e.section_id=? AND e.status='ACTIVE' AND u.active=TRUE",
            (rs, n) -> rs.getLong(1), section);
        Map<Long, String> submitted = AttendanceRules.completeRoster(roster, input.records());
        for (Long student : roster) {
            db.update("INSERT INTO attendance_records(class_session_id,student_user_id,status,marked_by) VALUES(?,?,?,?)" +
                " ON DUPLICATE KEY UPDATE status=VALUES(status),marked_by=VALUES(marked_by),marked_at=CURRENT_TIMESTAMP(6)",
                sessionId, student, submitted.get(student), uid);
        }
        db.update("UPDATE class_sessions SET state='COMPLETED',version=version+1 WHERE id=?", sessionId);
        app.audit(auth, "ATTENDANCE_SAVED", "CLASS_SESSION", sessionId);
        return Map.of("sessionId", sessionId, "saved", submitted.size(), "state", "COMPLETED", "version", version + 1);
    }

    /** Cancelled sessions are excluded from every percentage and report but their marks are kept. */
    @PostMapping("/sessions/{sessionId}/cancel") @Transactional
    Map<String, Object> cancel(@PathVariable long sessionId, Authentication auth) {
        sessionForFaculty(sessionId, app.userId(auth));
        int changed = db.update("UPDATE class_sessions SET state='CANCELLED',version=version+1 WHERE id=? AND state<>'CANCELLED'", sessionId);
        if (changed == 1) app.audit(auth, "SESSION_CANCELLED", "CLASS_SESSION", sessionId);
        return Map.of("sessionId", sessionId, "state", "CANCELLED");
    }

    private Assignment assignment(long id, long user) {
        var rows = db.query("SELECT a.section_id,a.subject_id,se.start_date,se.end_date" + LIVE_ASSIGNMENT + " AND a.id=? AND a.faculty_user_id=?",
            (rs, n) -> new Assignment(rs.getLong(1), rs.getLong(2), rs.getDate(3).toLocalDate(), rs.getDate(4).toLocalDate()), id, user);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This class is not assigned to you or is no longer active.");
        return rows.getFirst();
    }

    private Map<String, Object> sessionForFaculty(long session, long user) {
        var rows = db.query(
            "SELECT cs.id,cs.section_id,cs.subject_id,cs.session_date,cs.session_label,cs.state,cs.version FROM class_sessions cs" +
            " WHERE cs.id=? AND EXISTS(SELECT 1" + LIVE_ASSIGNMENT + " AND a.faculty_user_id=? AND a.section_id=cs.section_id AND a.subject_id=cs.subject_id)",
            (rs, n) -> {
                Map<String, Object> m = new HashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("sectionId", rs.getLong("section_id"));
                m.put("subjectId", rs.getLong("subject_id"));
                m.put("sessionDate", rs.getDate("session_date").toLocalDate());
                m.put("sessionLabel", rs.getString("session_label"));
                m.put("state", rs.getString("state"));
                m.put("version", rs.getLong("version"));
                return m;
            }, session, user);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This class session is not assigned to you or is no longer active.");
        return rows.getFirst();
    }
}
