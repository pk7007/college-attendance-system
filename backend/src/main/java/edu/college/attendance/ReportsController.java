package edu.college.attendance;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Reports read the same tables the student view reads, so the numbers shown to admin,
 * faculty and students always agree. Faculty only see classes they are actively assigned to.
 */
@RestController
@RequestMapping("/api/reports")
@PreAuthorize("hasAnyRole('ADMIN','FACULTY')")
public class ReportsController {
    static final int MAX_ROWS = 5000;
    private final JdbcTemplate db;
    public ReportsController(JdbcTemplate db) { this.db = db; }

    private record Filter(String where, List<Object> args) {}

    private Filter filter(Authentication auth, String from, String to, Long sectionId, Long subjectId) {
        LocalDate fromDate = AppSupport.optionalDate(from, "From"), toDate = AppSupport.optionalDate(to, "To");
        AppSupport.checkRange(fromDate, toDate);
        StringBuilder where = new StringBuilder(" WHERE cs.state='COMPLETED'");
        List<Object> args = new ArrayList<>();
        if (fromDate != null) { where.append(" AND cs.session_date>=?"); args.add(fromDate); }
        if (toDate != null) { where.append(" AND cs.session_date<=?"); args.add(toDate); }
        if (sectionId != null) { where.append(" AND cs.section_id=?"); args.add(sectionId); }
        if (subjectId != null) { where.append(" AND cs.subject_id=?"); args.add(subjectId); }
        if (auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_FACULTY"))) {
            // Faculty see exactly the classes on their class list: an active assignment to a live class.
            // Admins see everything, including history of classes that were later deactivated.
            where.append(" AND EXISTS(SELECT 1 FROM faculty_assignments fa JOIN users f ON f.id=fa.faculty_user_id" +
                " JOIN sections s ON s.id=fa.section_id JOIN subjects sub ON sub.id=fa.subject_id" + AppSupport.LIVE_CLASS_JOINS +
                " WHERE f.email=? AND fa.section_id=cs.section_id AND fa.subject_id=cs.subject_id AND fa.active=TRUE AND" + AppSupport.LIVE_CLASS_FILTER + ")");
            args.add(auth.getName());
        }
        return new Filter(where.toString(), args);
    }

    private static final String FROM =
        " FROM attendance_records ar JOIN class_sessions cs ON cs.id=ar.class_session_id JOIN sections s ON s.id=cs.section_id" +
        " JOIN courses c ON c.id=s.course_id JOIN subjects sub ON sub.id=cs.subject_id JOIN users u ON u.id=ar.student_user_id";

    @GetMapping("/attendance")
    List<Map<String, Object>> attendance(Authentication auth, @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                         @RequestParam(required = false) Long sectionId, @RequestParam(required = false) Long subjectId) {
        Filter f = filter(auth, from, to, sectionId, subjectId);
        return db.queryForList("SELECT cs.section_id sectionId,cs.session_date sessionDate,cs.session_label sessionLabel,c.name courseName,s.code sectionCode," +
            "sub.code subjectCode,sub.name subjectName,u.display_name studentName,u.email,ar.status" + FROM + f.where() +
            " ORDER BY cs.session_date DESC,c.name,s.code,cs.session_label,u.display_name LIMIT " + MAX_ROWS, f.args().toArray());
    }

    /** Attendance percentage per student per class, using the same formula as the student summary. */
    @GetMapping("/summary")
    List<Map<String, Object>> summary(Authentication auth, @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                      @RequestParam(required = false) Long sectionId, @RequestParam(required = false) Long subjectId) {
        Filter f = filter(auth, from, to, sectionId, subjectId);
        return db.queryForList("SELECT u.display_name studentName,u.email,s.code sectionCode,sub.code subjectCode,sub.name subjectName,COUNT(*) total," +
            " SUM(ar.status='PRESENT') present,SUM(ar.status='LATE') late,SUM(ar.status='ABSENT') absent," +
            " ROUND(100.0*SUM(ar.status IN ('PRESENT','LATE'))/COUNT(*),2) percentage" + FROM + f.where() +
            " GROUP BY u.id,u.display_name,u.email,s.id,s.code,sub.id,sub.code,sub.name ORDER BY s.code,sub.name,u.display_name LIMIT " + MAX_ROWS, f.args().toArray());
    }

    @GetMapping(value = "/attendance.csv", produces = "text/csv")
    ResponseEntity<byte[]> csv(Authentication auth, @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                               @RequestParam(required = false) Long sectionId, @RequestParam(required = false) Long subjectId) {
        List<Map<String, Object>> rows = attendance(auth, from, to, sectionId, subjectId);
        StringBuilder out = new StringBuilder("﻿Date,Session,Course,Section,Subject,Student,Email,Status\r\n");
        for (Map<String, Object> r : rows) {
            out.append(String.join(",", cell(r.get("sessionDate")), cell(r.get("sessionLabel")), cell(r.get("courseName")), cell(r.get("sectionCode")),
                cell(r.get("subjectName")), cell(r.get("studentName")), cell(r.get("email")), cell(r.get("status")))).append("\r\n");
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=attendance-report.csv")
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8)).body(out.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Quotes a CSV cell and neutralises spreadsheet formula injection. */
    static String cell(Object value) {
        String text = String.valueOf(value == null ? "" : value);
        if (text.matches("(?s)^[=+@\\-\\t\\r].*")) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
