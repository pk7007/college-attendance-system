package edu.college.attendance;

import static edu.college.attendance.AppSupport.LIVE_CLASS_FILTER;
import static edu.college.attendance.AppSupport.LIVE_CLASS_JOINS;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {
    private static final List<String> ROLES = List.of("ADMIN", "FACULTY", "STUDENT");
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;
    private final AppSupport app;

    public AdminController(JdbcTemplate db, PasswordEncoder passwords, AppSupport app) {
        this.db = db; this.passwords = passwords; this.app = app;
    }

    public record NewUser(@NotBlank @Size(max = 120) String displayName, @NotBlank @Email @Size(max = 254) String email,
                          @NotBlank String role, @NotBlank @Size(min = 12, max = 64) String password) {}
    public record ResetPassword(@NotBlank @Size(min = 12, max = 64) String password) {}
    public record AcademicSetup(
        @NotBlank @Size(max = 30) String departmentCode, @NotBlank @Size(max = 120) String departmentName,
        @NotBlank @Size(max = 30) String yearLabel, @NotNull LocalDate yearStart, @NotNull LocalDate yearEnd,
        @NotBlank @Size(max = 50) String semester, @NotNull LocalDate semesterStart, @NotNull LocalDate semesterEnd,
        @NotBlank @Size(max = 30) String courseCode, @NotBlank @Size(max = 120) String courseName,
        @NotBlank @Size(max = 30) String sectionCode,
        @NotBlank @Size(max = 30) String subjectCode, @NotBlank @Size(max = 120) String subjectName) {}
    public record Link(@NotNull Long userId, @NotNull Long sectionId, @NotNull Long subjectId) {}
    public record Enrollment(@NotNull Long studentId, @NotNull Long sectionId) {}

    // ---- users -------------------------------------------------------------------------

    @GetMapping("/users")
    List<Map<String, Object>> users() {
        return db.queryForList("SELECT id,email,display_name displayName,role,active,must_change_password mustChangePassword,created_at createdAt FROM users ORDER BY display_name,id");
    }

    @PostMapping("/users") @Transactional
    Map<String, Object> createUser(@Valid @RequestBody NewUser input, Authentication auth) {
        String role = input.role().trim().toUpperCase();
        if (!ROLES.contains(role)) throw new IllegalArgumentException("Role must be ADMIN, FACULTY, or STUDENT.");
        String email = input.email().trim().toLowerCase();
        db.update("INSERT INTO users(email,password_hash,display_name,role,must_change_password) VALUES(?,?,?,?,TRUE)",
            email, passwords.encode(input.password()), input.displayName().trim(), role);
        Map<String, Object> created = db.queryForMap("SELECT id,email,display_name displayName,role,active,must_change_password mustChangePassword FROM users WHERE email=?", email);
        app.audit(auth, "USER_CREATED", "USER", ((Number) created.get("id")).longValue());
        return created;
    }

    @PostMapping("/users/{id}/deactivate") @Transactional
    Map<String, Object> deactivate(@PathVariable long id, Authentication auth) {
        setUserActive(db, app.userId(auth), id, false);
        app.audit(auth, "USER_DEACTIVATED", "USER", id);
        return Map.of("id", id, "active", false);
    }

    @PostMapping("/users/{id}/password") @Transactional
    Map<String, Object> resetPassword(@PathVariable long id, @Valid @RequestBody ResetPassword input, Authentication auth) {
        int changed = db.update("UPDATE users SET password_hash=?,must_change_password=TRUE WHERE id=?", passwords.encode(input.password()), id);
        if (changed != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found.");
        app.audit(auth, "PASSWORD_RESET", "USER", id);
        return Map.of("id", id, "mustChangePassword", true);
    }

    /**
     * Activates or deactivates a user. Admins cannot deactivate themselves, and the admin rows are
     * locked so two concurrent requests cannot both disable "another" admin and leave the college with none.
     */
    static void setUserActive(JdbcTemplate db, long actorId, long id, boolean active) {
        if (!active) {
            if (id == actorId) throw new IllegalArgumentException("You cannot deactivate your own account. Ask another administrator.");
            List<Long> admins = db.query("SELECT id FROM users WHERE role='ADMIN' AND active=TRUE FOR UPDATE", (rs, n) -> rs.getLong(1));
            if (admins.contains(id) && admins.size() <= 1) throw new IllegalArgumentException("The last active administrator cannot be deactivated.");
        }
        if (db.update("UPDATE users SET active=? WHERE id=?", active, id) != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found.");
    }

    // ---- classes -----------------------------------------------------------------------

    /** Every live section/subject pair with its assigned faculty and active roster size. */
    @GetMapping("/classes")
    List<Map<String, Object>> classes() {
        return db.queryForList(
            "SELECT s.id sectionId,c.name courseName,s.code sectionCode,se.label semester,sub.id subjectId,sub.code subjectCode,sub.name subjectName," +
            " (SELECT GROUP_CONCAT(u.display_name ORDER BY u.display_name SEPARATOR ', ') FROM faculty_assignments fa JOIN users u ON u.id=fa.faculty_user_id" +
            "   WHERE fa.section_id=s.id AND fa.subject_id=sub.id AND fa.active=TRUE AND u.active=TRUE) faculty," +
            " (SELECT COUNT(*) FROM student_enrollments e JOIN users u ON u.id=e.student_user_id WHERE e.section_id=s.id AND e.status='ACTIVE' AND u.active=TRUE) students" +
            " FROM sections s JOIN subjects sub ON sub.course_id=s.course_id" + LIVE_CLASS_JOINS +
            " WHERE" + LIVE_CLASS_FILTER + " ORDER BY c.name,s.code,sub.name");
    }

    /**
     * Creates (or re-uses) the department, year, semester, course, section and subject.
     * Re-submitting codes that were deactivated reactivates them so the class is visible again.
     */
    @PostMapping("/academic/setup") @Transactional
    Map<String, Object> setup(@Valid @RequestBody AcademicSetup a, Authentication auth) {
        if (a.yearEnd().isBefore(a.yearStart())) throw new IllegalArgumentException("Academic year must end on or after it starts.");
        if (a.semesterEnd().isBefore(a.semesterStart())) throw new IllegalArgumentException("Semester must end on or after it starts.");

        String departmentCode = a.departmentCode().trim().toUpperCase();
        long department = ensure("SELECT id FROM departments WHERE code=?", "departments",
            () -> db.update("INSERT INTO departments(code,name) VALUES(?,?)", departmentCode, a.departmentName().trim()), departmentCode);

        String yearLabel = a.yearLabel().trim();
        long year = ensure("SELECT id FROM academic_years WHERE label=?", "academic_years",
            () -> db.update("INSERT INTO academic_years(label,start_date,end_date) VALUES(?,?,?)", yearLabel, a.yearStart(), a.yearEnd()), yearLabel);
        Map<String, Object> yearDates = db.queryForMap("SELECT start_date,end_date FROM academic_years WHERE id=?", year);
        LocalDate yearStart = ((java.sql.Date) yearDates.get("start_date")).toLocalDate();
        LocalDate yearEnd = ((java.sql.Date) yearDates.get("end_date")).toLocalDate();
        if (!yearStart.equals(a.yearStart()) || !yearEnd.equals(a.yearEnd()))
            throw new IllegalArgumentException("Academic year " + yearLabel + " already exists with dates " + yearStart + " to " + yearEnd + ". Use those dates or a different year label.");
        if (a.semesterStart().isBefore(yearStart) || a.semesterEnd().isAfter(yearEnd))
            throw new IllegalArgumentException("Semester dates must fall within academic year " + yearLabel + " (" + yearStart + " to " + yearEnd + ").");

        String semesterLabel = a.semester().trim();
        long semester = ensure("SELECT id FROM semesters WHERE academic_year_id=? AND label=?", "semesters",
            () -> db.update("INSERT INTO semesters(academic_year_id,label,start_date,end_date) VALUES(?,?,?,?)", year, semesterLabel, a.semesterStart(), a.semesterEnd()),
            year, semesterLabel);
        Map<String, Object> semesterDates = db.queryForMap("SELECT start_date,end_date FROM semesters WHERE id=?", semester);
        LocalDate semesterStart = ((java.sql.Date) semesterDates.get("start_date")).toLocalDate();
        LocalDate semesterEnd = ((java.sql.Date) semesterDates.get("end_date")).toLocalDate();
        if (!semesterStart.equals(a.semesterStart()) || !semesterEnd.equals(a.semesterEnd()))
            throw new IllegalArgumentException(semesterLabel + " of " + yearLabel + " already exists with dates " + semesterStart + " to " + semesterEnd + ". Use those dates or a different semester name.");

        String courseCode = a.courseCode().trim().toUpperCase();
        long course = ensure("SELECT id FROM courses WHERE department_id=? AND code=?", "courses",
            () -> db.update("INSERT INTO courses(department_id,code,name) VALUES(?,?,?)", department, courseCode, a.courseName().trim()), department, courseCode);

        String sectionCode = a.sectionCode().trim().toUpperCase();
        long section = ensure("SELECT id FROM sections WHERE course_id=? AND semester_id=? AND code=?", "sections",
            () -> db.update("INSERT INTO sections(course_id,semester_id,code) VALUES(?,?,?)", course, semester, sectionCode), course, semester, sectionCode);

        String subjectCode = a.subjectCode().trim().toUpperCase();
        long subject = ensure("SELECT id FROM subjects WHERE course_id=? AND code=?", "subjects",
            () -> db.update("INSERT INTO subjects(course_id,code,name) VALUES(?,?,?)", course, subjectCode, a.subjectName().trim()), course, subjectCode);

        app.audit(auth, "ACADEMIC_CLASS_CREATED", "SECTION", section);
        return Map.of("sectionId", section, "subjectId", subject, "message", "Class " + sectionCode + " · " + subjectCode + " is ready.");
    }

    // ---- enrollments and assignments ----------------------------------------------------

    @GetMapping("/enrollments")
    List<Map<String, Object>> enrollments() {
        return db.queryForList("SELECT e.id,e.student_user_id studentId,u.display_name studentName,e.section_id sectionId,s.code sectionCode,e.status FROM student_enrollments e JOIN users u ON u.id=e.student_user_id JOIN sections s ON s.id=e.section_id ORDER BY s.code,u.display_name");
    }

    @PostMapping("/enrollments") @Transactional
    Map<String, Object> enroll(@Valid @RequestBody Enrollment e, Authentication auth) {
        requireRole(e.studentId(), "STUDENT");
        requireLiveSection(e.sectionId());
        // A single upsert is atomic; a locking read on a missing row would let two admins deadlock.
        List<String> before = db.query("SELECT status FROM student_enrollments WHERE student_user_id=? AND section_id=?",
            (rs, n) -> rs.getString(1), e.studentId(), e.sectionId());
        if (!before.isEmpty() && "ACTIVE".equals(before.getFirst()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This student is already enrolled in that section.");
        db.update("INSERT INTO student_enrollments(student_user_id,section_id) VALUES(?,?) ON DUPLICATE KEY UPDATE status='ACTIVE'", e.studentId(), e.sectionId());
        String result = before.isEmpty() ? "enrolled" : "reactivated";
        app.audit(auth, "STUDENT_ENROLLED", "SECTION", e.sectionId());
        return Map.of("studentId", e.studentId(), "sectionId", e.sectionId(), "status", result);
    }

    @PostMapping("/assignments") @Transactional
    Map<String, Object> assign(@Valid @RequestBody Link l, Authentication auth) {
        requireRole(l.userId(), "FACULTY");
        Integer live = db.queryForObject("SELECT COUNT(*) FROM sections s JOIN subjects sub ON sub.id=?" + LIVE_CLASS_JOINS + " WHERE s.id=? AND" + LIVE_CLASS_FILTER,
            Integer.class, l.subjectId(), l.sectionId());
        if (live == null || live == 0) throw new IllegalArgumentException("Choose an active class: the subject must belong to the section's course.");
        List<Boolean> before = db.query("SELECT active FROM faculty_assignments WHERE faculty_user_id=? AND section_id=? AND subject_id=?",
            (rs, n) -> rs.getBoolean(1), l.userId(), l.sectionId(), l.subjectId());
        if (!before.isEmpty() && before.getFirst())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This faculty member is already assigned to that class.");
        db.update("INSERT INTO faculty_assignments(faculty_user_id,section_id,subject_id) VALUES(?,?,?) ON DUPLICATE KEY UPDATE active=TRUE",
            l.userId(), l.sectionId(), l.subjectId());
        String result = before.isEmpty() ? "assigned" : "reactivated";
        app.audit(auth, "FACULTY_ASSIGNED", "SECTION", l.sectionId());
        return Map.of("facultyId", l.userId(), "sectionId", l.sectionId(), "subjectId", l.subjectId(), "status", result);
    }

    // ---- helpers -----------------------------------------------------------------------

    private void requireRole(long id, String role) {
        Integer found = db.queryForObject("SELECT COUNT(*) FROM users WHERE id=? AND role=? AND active=TRUE", Integer.class, id, role);
        if (found == null || found == 0) throw new IllegalArgumentException("Selected user must be an active " + role.toLowerCase() + " account.");
    }

    private void requireLiveSection(long sectionId) {
        Integer found = db.queryForObject("SELECT COUNT(*) FROM sections s" + LIVE_CLASS_JOINS +
            " WHERE s.id=? AND s.active=TRUE AND c.active=TRUE AND d.active=TRUE AND se.active=TRUE", Integer.class, sectionId);
        if (found == null || found == 0) throw new IllegalArgumentException("Choose an active section.");
    }

    /** Finds a row by natural key, inserting it when missing and reactivating it when inactive. */
    private long ensure(String findSql, String table, Runnable insert, Object... key) {
        List<Long> rows = db.query(findSql, (rs, n) -> rs.getLong(1), key);
        if (rows.isEmpty()) {
            insert.run();
            rows = db.query(findSql, (rs, n) -> rs.getLong(1), key);
        } else {
            db.update("UPDATE " + table + " SET active=TRUE WHERE id=? AND active=FALSE", rows.getFirst());
        }
        return rows.getFirst();
    }
}
