package edu.college.attendance;

import static edu.college.attendance.AppSupport.LIVE_CLASS_FILTER;
import static edu.college.attendance.AppSupport.LIVE_CLASS_JOINS;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Student routes always derive identity from the session; a student can never request another student's data. */
@RestController
@RequestMapping("/api/student")
@PreAuthorize("hasRole('STUDENT')")
public class StudentController {
    private final JdbcTemplate db;
    private final AppSupport app;
    public StudentController(JdbcTemplate db, AppSupport app) { this.db = db; this.app = app; }

    @GetMapping("/attendance/summary")
    Map<String, Object> summary(Authentication auth) {
        long uid = app.userId(auth);
        Map<String, Object> counts = db.queryForMap(
            "SELECT COUNT(*) total,COALESCE(SUM(ar.status='PRESENT'),0) present,COALESCE(SUM(ar.status='ABSENT'),0) absent," +
            " COALESCE(SUM(ar.status='LATE'),0) late,COALESCE(SUM(ar.status IN ('PRESENT','LATE')),0) attended" +
            " FROM attendance_records ar JOIN class_sessions cs ON cs.id=ar.class_session_id WHERE ar.student_user_id=? AND cs.state='COMPLETED'", uid);
        long total = ((Number) counts.get("total")).longValue();
        long attended = ((Number) counts.get("attended")).longValue();
        counts.put("percentage", total == 0 ? null : Math.round(attended * 10000.0 / total) / 100.0);
        counts.put("formula", "(PRESENT + LATE) / completed sessions × 100; cancelled and unsaved sessions are excluded.");
        counts.put("subjects", db.queryForList(
            "SELECT sub.id subjectId,sub.code subjectCode,sub.name subjectName,COUNT(*) total,SUM(ar.status IN ('PRESENT','LATE')) attended," +
            " ROUND(100.0*SUM(ar.status IN ('PRESENT','LATE'))/COUNT(*),2) percentage" +
            " FROM attendance_records ar JOIN class_sessions cs ON cs.id=ar.class_session_id JOIN subjects sub ON sub.id=cs.subject_id" +
            " WHERE ar.student_user_id=? AND cs.state='COMPLETED' GROUP BY sub.id,sub.code,sub.name ORDER BY sub.name", uid));
        return counts;
    }

    @GetMapping("/attendance/history")
    Map<String, Object> history(Authentication auth, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
                                @RequestParam(required = false) Long subjectId, @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Page must be 0 or more and size between 1 and 100.");
        LocalDate fromDate = AppSupport.optionalDate(from, "From"), toDate = AppSupport.optionalDate(to, "To");
        AppSupport.checkRange(fromDate, toDate);
        StringBuilder where = new StringBuilder(" WHERE ar.student_user_id=? AND cs.state='COMPLETED'");
        List<Object> args = new ArrayList<>(List.of(app.userId(auth)));
        if (subjectId != null) { where.append(" AND cs.subject_id=?"); args.add(subjectId); }
        if (fromDate != null) { where.append(" AND cs.session_date>=?"); args.add(fromDate); }
        if (toDate != null) { where.append(" AND cs.session_date<=?"); args.add(toDate); }
        Long total = db.queryForObject("SELECT COUNT(*) FROM attendance_records ar JOIN class_sessions cs ON cs.id=ar.class_session_id" + where, Long.class, args.toArray());
        String sql = "SELECT ar.id,cs.session_date sessionDate,cs.session_label sessionLabel,sub.code subjectCode,sub.name subjectName,ar.status" +
            " FROM attendance_records ar JOIN class_sessions cs ON cs.id=ar.class_session_id JOIN subjects sub ON sub.id=cs.subject_id" +
            where + " ORDER BY cs.session_date DESC,cs.id DESC LIMIT ? OFFSET ?";
        args.add(size);
        args.add((long) page * size);
        return Map.of("content", db.queryForList(sql, args.toArray()), "page", page, "size", size, "totalElements", total == null ? 0 : total);
    }

    /** The classes this student is currently enrolled in, with the faculty teaching each subject. */
    @GetMapping("/classes")
    List<Map<String, Object>> classes(Authentication auth) {
        return db.queryForList(
            "SELECT s.id sectionId,s.code sectionCode,c.name courseName,se.label semester,sub.id subjectId,sub.code subjectCode,sub.name subjectName," +
            " (SELECT GROUP_CONCAT(u.display_name ORDER BY u.display_name SEPARATOR ', ') FROM faculty_assignments fa JOIN users u ON u.id=fa.faculty_user_id" +
            "   WHERE fa.section_id=s.id AND fa.subject_id=sub.id AND fa.active=TRUE AND u.active=TRUE) faculty" +
            " FROM student_enrollments e JOIN sections s ON s.id=e.section_id JOIN subjects sub ON sub.course_id=s.course_id" + LIVE_CLASS_JOINS +
            " WHERE e.student_user_id=? AND e.status='ACTIVE' AND" + LIVE_CLASS_FILTER + " ORDER BY c.name,s.code,sub.name", app.userId(auth));
    }
}
