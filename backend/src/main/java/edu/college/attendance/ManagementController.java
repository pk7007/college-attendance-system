package edu.college.attendance;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Generic rename / activate / deactivate for admin-managed records. Table and column names
 * come only from the fixed whitelist below, never from user input, so the dynamic SQL is safe.
 * Deactivating a record hides it from every role (see AppSupport.LIVE_CLASS_FILTER) while keeping saved attendance.
 */
@RestController
@RequestMapping("/api/admin/manage")
@PreAuthorize("hasRole('ADMIN')")
public class ManagementController {
    private record Resource(String table, String field, int maxLength, String listSql) {}

    private static final Map<String, Resource> RESOURCES = Map.of(
        "users", new Resource("users", "display_name", 120,
            "SELECT id,CONCAT(display_name,' · ',LOWER(role),' · ',email) label,display_name value,active FROM users ORDER BY role,display_name,id"),
        "departments", new Resource("departments", "name", 120,
            "SELECT id,CONCAT(code,' · ',name) label,name value,active FROM departments ORDER BY code"),
        "semesters", new Resource("semesters", "label", 50,
            "SELECT se.id,CONCAT(y.label,' · ',se.label,' (',se.start_date,' to ',se.end_date,')') label,se.label value,se.active FROM semesters se JOIN academic_years y ON y.id=se.academic_year_id ORDER BY se.start_date,se.id"),
        "courses", new Resource("courses", "name", 120,
            "SELECT c.id,CONCAT(d.code,' · ',c.code,' · ',c.name) label,c.name value,c.active FROM courses c JOIN departments d ON d.id=c.department_id ORDER BY d.code,c.code"),
        "subjects", new Resource("subjects", "name", 120,
            "SELECT sub.id,CONCAT(c.code,' · ',sub.code,' · ',sub.name) label,sub.name value,sub.active FROM subjects sub JOIN courses c ON c.id=sub.course_id ORDER BY c.code,sub.code"),
        "sections", new Resource("sections", "code", 30,
            "SELECT s.id,CONCAT(c.code,' · ',se.label,' · ',s.code) label,s.code value,s.active FROM sections s JOIN courses c ON c.id=s.course_id JOIN semesters se ON se.id=s.semester_id ORDER BY c.code,s.code"),
        "student_enrollments", new Resource("student_enrollments", null, 0,
            "SELECT e.id,CONCAT(u.display_name,' · ',s.code) label,e.status value,e.status='ACTIVE' active FROM student_enrollments e JOIN users u ON u.id=e.student_user_id JOIN sections s ON s.id=e.section_id ORDER BY s.code,u.display_name"),
        "faculty_assignments", new Resource("faculty_assignments", null, 0,
            "SELECT a.id,CONCAT(u.display_name,' · ',s.code,' · ',sub.name) label,IF(a.active,'ACTIVE','INACTIVE') value,a.active FROM faculty_assignments a JOIN users u ON u.id=a.faculty_user_id JOIN sections s ON s.id=a.section_id JOIN subjects sub ON sub.id=a.subject_id ORDER BY s.code,sub.name,u.display_name"));

    private final JdbcTemplate db;
    private final AppSupport app;
    public ManagementController(JdbcTemplate db, AppSupport app) { this.db = db; this.app = app; }

    private static Resource resource(String entity) {
        Resource r = RESOURCES.get(entity);
        if (r == null) throw new IllegalArgumentException("Unknown resource.");
        return r;
    }

    @GetMapping("/{entity}")
    List<Map<String, Object>> list(@PathVariable String entity) {
        return db.queryForList(resource(entity).listSql());
    }

    public record Update(String value, Boolean active) {}

    @PutMapping("/{entity}/{id}") @Transactional
    Map<String, Object> update(@PathVariable String entity, @PathVariable long id, @RequestBody Update input, Authentication auth) {
        Resource r = resource(entity);
        if (input == null || input.active() == null) throw new IllegalArgumentException("Active status is required.");
        int changed;
        switch (entity) {
            case "student_enrollments" -> changed = db.update("UPDATE student_enrollments SET status=? WHERE id=?", input.active() ? "ACTIVE" : "INACTIVE", id);
            case "faculty_assignments" -> changed = db.update("UPDATE faculty_assignments SET active=? WHERE id=?", input.active(), id);
            default -> {
                String value = input.value() == null ? "" : input.value().trim();
                if (value.isEmpty() || value.length() > r.maxLength()) throw new IllegalArgumentException("Enter a name or code of 1 to " + r.maxLength() + " characters.");
                if (entity.equals("sections")) value = value.toUpperCase();
                if (entity.equals("users")) AdminController.setUserActive(db, app.userId(auth), id, input.active());
                changed = db.update("UPDATE " + r.table() + " SET " + r.field() + "=?,active=? WHERE id=?", value, input.active(), id);
            }
        }
        if (changed != 1) throw new IllegalArgumentException("Record not found.");
        app.audit(auth, "RECORD_UPDATED", entity.toUpperCase(), id);
        return Map.of("id", id, "updated", true);
    }
}
