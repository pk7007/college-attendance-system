package edu.college.attendance;

import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Startup data.
 * <ul>
 *   <li>Any profile: creates the first administrator from CAM_BOOTSTRAP_ADMIN_EMAIL / CAM_BOOTSTRAP_ADMIN_PASSWORD
 *       if that account does not exist yet, so a fresh database is never left without a way to sign in.</li>
 *   <li>dev profile only: adds one demo faculty, one demo student and a sample class on an empty database.
 *       Dates are relative to today so the demo semester is always current.</li>
 * </ul>
 * Existing accounts are never modified, so changing the configuration does not reset passwords.
 */
@Configuration
public class DevelopmentBootstrap {
    private static final Logger log = LoggerFactory.getLogger(DevelopmentBootstrap.class);

    @Bean
    ApplicationRunner startupData(JdbcTemplate db, PasswordEncoder passwords, TransactionTemplate tx, Environment env,
                                  @Value("${CAM_BOOTSTRAP_ADMIN_EMAIL:}") String adminEmail,
                                  @Value("${CAM_BOOTSTRAP_ADMIN_PASSWORD:}") String adminPassword,
                                  @Value("${CAM_DEMO_FACULTY_PASSWORD:FacultyDemo-2026!}") String facultyPassword,
                                  @Value("${CAM_DEMO_STUDENT_PASSWORD:StudentDemo-2026!}") String studentPassword,
                                  @Value("${CAM_BOOTSTRAP_ADMIN_MUST_CHANGE:}") String adminMustChange) {
        boolean dev = env.acceptsProfiles(Profiles.of("dev"));
        // Outside the demo, the configured password is often a copied default, so the first sign-in must replace it.
        boolean forceChange = adminMustChange.isBlank() ? !dev : Boolean.parseBoolean(adminMustChange.trim());
        return args -> tx.executeWithoutResult(status -> {
            if (!adminEmail.isBlank()) {
                if (adminPassword.length() < 12) log.warn("CAM_BOOTSTRAP_ADMIN_PASSWORD must be at least 12 characters; no administrator was created.");
                else if (addUser(db, passwords, adminEmail, "Campus Administrator", "ADMIN", adminPassword, forceChange)) log.info("Created administrator {}", adminEmail.trim().toLowerCase());
            }
            Integer admins = db.queryForObject("SELECT COUNT(*) FROM users WHERE role='ADMIN' AND active=TRUE", Integer.class);
            if (admins == null || admins == 0) log.warn("No active administrator exists. Set CAM_BOOTSTRAP_ADMIN_EMAIL and CAM_BOOTSTRAP_ADMIN_PASSWORD and restart.");
            if (dev) seedDemo(db, passwords, facultyPassword, studentPassword);
        });
    }

    private void seedDemo(JdbcTemplate db, PasswordEncoder encoder, String facultyPassword, String studentPassword) {
        addUser(db, encoder, "faculty@college.test", "Jordan Lee", "FACULTY", facultyPassword, false);
        addUser(db, encoder, "student@college.test", "Sam Patel", "STUDENT", studentPassword, false);
        Integer exists = db.queryForObject("SELECT COUNT(*) FROM departments", Integer.class);
        if (exists != null && exists > 0) return; // only seed classes into an empty database

        // Same calendar the admin "Create class" form suggests: June–May academic years with
        // Semester 1 = June–November and Semester 2 = December–May. Seed the semester containing today.
        LocalDate today = LocalDate.now();
        int startYear = today.getMonthValue() >= 6 ? today.getYear() : today.getYear() - 1;
        boolean first = today.getMonthValue() >= 6 && today.getMonthValue() <= 11;
        LocalDate yearStart = LocalDate.of(startYear, 6, 1), yearEnd = LocalDate.of(startYear + 1, 5, 31);
        LocalDate semesterStart = first ? yearStart : LocalDate.of(startYear, 12, 1), semesterEnd = first ? LocalDate.of(startYear, 11, 30) : yearEnd;
        String yearLabel = startYear + "-" + (startYear + 1), semesterLabel = first ? "Semester 1" : "Semester 2";

        db.update("INSERT INTO departments(code,name) VALUES('CS','Computer Science')");
        db.update("INSERT INTO academic_years(label,start_date,end_date) VALUES(?,?,?)", yearLabel, yearStart, yearEnd);
        db.update("INSERT INTO semesters(academic_year_id,label,start_date,end_date) SELECT id,?,?,? FROM academic_years WHERE label=?", semesterLabel, semesterStart, semesterEnd, yearLabel);
        db.update("INSERT INTO courses(department_id,code,name) SELECT id,'BSC-CS','B.Sc. Computer Science' FROM departments WHERE code='CS'");
        db.update("INSERT INTO sections(course_id,semester_id,code) SELECT c.id,se.id,'CS-2A' FROM courses c CROSS JOIN semesters se WHERE c.code='BSC-CS' AND se.label=?", semesterLabel);
        db.update("INSERT INTO subjects(course_id,code,name) SELECT id,'CS201','Data Structures' FROM courses WHERE code='BSC-CS'");
        db.update("INSERT INTO faculty_assignments(faculty_user_id,section_id,subject_id) SELECT f.id,s.id,sub.id FROM users f CROSS JOIN sections s CROSS JOIN subjects sub WHERE f.email='faculty@college.test' AND s.code='CS-2A' AND sub.code='CS201'");
        db.update("INSERT INTO student_enrollments(student_user_id,section_id) SELECT u.id,s.id FROM users u CROSS JOIN sections s WHERE u.email='student@college.test' AND s.code='CS-2A'");
        sampleSession(db, latest(today.minusDays(2), semesterStart), "Lecture 1", "PRESENT");
        sampleSession(db, latest(today.minusDays(1), semesterStart), "Lecture 2", "LATE");
        log.info("Seeded demo class CS-2A / CS201 for semester {} to {}", semesterStart, semesterEnd);
    }

    private static LocalDate latest(LocalDate a, LocalDate b) { return a.isAfter(b) ? a : b; }

    private void sampleSession(JdbcTemplate db, LocalDate date, String label, String status) {
        db.update("INSERT INTO class_sessions(section_id,subject_id,session_date,session_label,state,version,created_by) SELECT s.id,sub.id,?,?,'COMPLETED',1,f.id" +
            " FROM sections s JOIN subjects sub ON sub.course_id=s.course_id JOIN users f ON f.email='faculty@college.test' WHERE s.code='CS-2A' AND sub.code='CS201'", date, label);
        db.update("INSERT INTO attendance_records(class_session_id,student_user_id,status,marked_by) SELECT cs.id,stu.id,?,f.id FROM class_sessions cs" +
            " JOIN users stu ON stu.email='student@college.test' JOIN users f ON f.email='faculty@college.test' WHERE cs.session_label=? AND cs.session_date=?", status, label, date);
    }

    /** Inserts the account if the email is new. Returns true when a row was created. */
    private boolean addUser(JdbcTemplate db, PasswordEncoder encoder, String email, String name, String role, String password, boolean mustChange) {
        String normalized = email.trim().toLowerCase();
        Integer exists = db.queryForObject("SELECT COUNT(*) FROM users WHERE email=?", Integer.class, normalized);
        if (exists != null && exists > 0) return false;
        db.update("INSERT INTO users(email,password_hash,display_name,role,must_change_password) VALUES(?,?,?,?,?)", normalized, encoder.encode(password), name, role, mustChange);
        return true;
    }
}
