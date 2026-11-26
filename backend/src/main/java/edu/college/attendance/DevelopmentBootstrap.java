package edu.college.attendance;

import java.time.LocalDate;
import java.util.List;
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

    // ---- demo data: one section, 3 subjects, 2 faculty, 12 students, the last month of classes ----------

    /** Demo faculty: email, name. All use CAM_DEMO_FACULTY_PASSWORD. */
    private static final String[][] DEMO_FACULTY = {{"faculty@college.test", "Jordan Lee"}, {"faculty2@college.test", "Anita Rao"}};
    /**
     * Demo students: email, name, typical attendance rate. A few sit below the common 75% threshold
     * so reports show realistic variety. All use CAM_DEMO_STUDENT_PASSWORD.
     */
    private static final Object[][] DEMO_STUDENTS = {
        {"student@college.test", "Sam Patel", 0.94}, {"student2@college.test", "Aisha Khan", 0.97}, {"student3@college.test", "Ravi Kumar", 0.82},
        {"student4@college.test", "Meera Iyer", 0.99}, {"student5@college.test", "Arjun Nair", 0.68}, {"student6@college.test", "Fatima Sheikh", 0.90},
        {"student7@college.test", "Daniel Thomas", 0.86}, {"student8@college.test", "Kavya Reddy", 0.62}, {"student9@college.test", "Rohan Das", 0.91},
        {"student10@college.test", "Neha Joshi", 0.95}, {"student11@college.test", "Vikram Singh", 0.74}, {"student12@college.test", "Ananya Gupta", 0.88}};
    /** Demo subjects: code, name, teacher (index into DEMO_FACULTY), weekdays it meets. */
    private record DemoSubject(String code, String name, int teacher, java.util.Set<java.time.DayOfWeek> days) {}
    private static final List<DemoSubject> DEMO_SUBJECTS = List.of(
        new DemoSubject("CS201", "Data Structures", 0, java.util.EnumSet.of(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.FRIDAY)),
        new DemoSubject("CS202", "Database Systems", 0, java.util.EnumSet.of(java.time.DayOfWeek.TUESDAY, java.time.DayOfWeek.THURSDAY)),
        new DemoSubject("CS203", "Operating Systems", 1, java.util.EnumSet.of(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.THURSDAY)));

    private void seedDemo(JdbcTemplate db, PasswordEncoder encoder, String facultyPassword, String studentPassword) {
        String facultyHash = encoder.encode(facultyPassword), studentHash = encoder.encode(studentPassword);
        for (String[] f : DEMO_FACULTY) addUserWithHash(db, f[0], f[1], "FACULTY", facultyHash);
        for (Object[] s : DEMO_STUDENTS) addUserWithHash(db, (String) s[0], (String) s[1], "STUDENT", studentHash);
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
        long section = db.queryForObject("SELECT id FROM sections WHERE code='CS-2A'", Long.class);
        long[] faculty = new long[DEMO_FACULTY.length];
        for (int i = 0; i < DEMO_FACULTY.length; i++) faculty[i] = userId(db, DEMO_FACULTY[i][0]);
        long[] students = new long[DEMO_STUDENTS.length];
        for (int i = 0; i < DEMO_STUDENTS.length; i++) {
            students[i] = userId(db, (String) DEMO_STUDENTS[i][0]);
            db.update("INSERT INTO student_enrollments(student_user_id,section_id) VALUES(?,?)", students[i], section);
        }

        // About a month of past classes (from 30 days ago, or the semester start if later, up to yesterday),
        // so faculty can still open today's session themselves. Fixed random seed: the same demo every time.
        java.util.Random random = new java.util.Random(2026);
        LocalDate from = today.minusDays(30).isBefore(semesterStart) ? semesterStart : today.minusDays(30);
        int sessions = 0, records = 0;
        for (DemoSubject subject : DEMO_SUBJECTS) {
            db.update("INSERT INTO subjects(course_id,code,name) SELECT id,?,? FROM courses WHERE code='BSC-CS'", subject.code(), subject.name());
            long subjectId = db.queryForObject("SELECT id FROM subjects WHERE code=?", Long.class, subject.code());
            long teacher = faculty[subject.teacher()];
            db.update("INSERT INTO faculty_assignments(faculty_user_id,section_id,subject_id) VALUES(?,?,?)", teacher, section, subjectId);
            int lecture = 0;
            for (LocalDate day = from; day.isBefore(today); day = day.plusDays(1)) {
                if (!subject.days().contains(day.getDayOfWeek())) continue;
                lecture++;
                // One class in the month was cancelled (e.g. a holiday): it is kept but excluded from every total.
                boolean cancelled = subject.code().equals("CS203") && lecture == 3;
                db.update("INSERT INTO class_sessions(section_id,subject_id,session_date,session_label,state,version,created_by) VALUES(?,?,?,?,?,1,?)",
                    section, subjectId, day, "Lecture " + lecture, cancelled ? "CANCELLED" : "COMPLETED", teacher);
                if (cancelled) continue;
                long sessionId = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                List<Object[]> marks = new java.util.ArrayList<>();
                for (int i = 0; i < students.length; i++) {
                    double rate = (Double) DEMO_STUDENTS[i][2], roll = random.nextDouble();
                    String status = roll < rate * 0.92 ? "PRESENT" : roll < rate ? "LATE" : "ABSENT";
                    marks.add(new Object[]{sessionId, students[i], status, teacher});
                }
                db.batchUpdate("INSERT INTO attendance_records(class_session_id,student_user_id,status,marked_by) VALUES(?,?,?,?)", marks);
                sessions++;
                records += marks.size();
            }
        }
        log.info("Seeded demo section CS-2A ({} to {}): {} subjects, {} students, {} completed sessions, {} attendance marks",
            semesterStart, semesterEnd, DEMO_SUBJECTS.size(), students.length, sessions, records);
    }

    private static long userId(JdbcTemplate db, String email) {
        return db.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    }

    /** Like addUser, but with a precomputed hash so a dozen demo accounts don't each pay for BCrypt. */
    private void addUserWithHash(JdbcTemplate db, String email, String name, String role, String passwordHash) {
        Integer exists = db.queryForObject("SELECT COUNT(*) FROM users WHERE email=?", Integer.class, email);
        if (exists == null || exists == 0)
            db.update("INSERT INTO users(email,password_hash,display_name,role,must_change_password) VALUES(?,?,?,?,FALSE)", email, passwordHash, name, role);
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
