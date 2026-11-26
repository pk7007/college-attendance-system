package edu.college.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * End-to-end checks against a real MySQL database that a change made by one role is reflected
 * correctly for every other role. Each test signs in as several users at once (admin, faculty and
 * students, each with their own session) and creates its own uniquely coded class, so tests are independent.
 *
 * <p>Runs only when CAM_IT_DB_URL points to a disposable MySQL database, because the schema is wiped first.
 * Example: CAM_IT_DB_URL=jdbc:mysql://127.0.0.1:3306/cams_it CAM_IT_DB_USER=attendance CAM_IT_DB_PASSWORD=... ./mvnw verify
 */
@SpringBootTest(properties = {
    "spring.profiles.active=test",
    "spring.flyway.clean-disabled=false",
    "CAM_BOOTSTRAP_ADMIN_EMAIL=" + DataReflectionIntegrationTest.ADMIN_EMAIL,
    "CAM_BOOTSTRAP_ADMIN_PASSWORD=" + DataReflectionIntegrationTest.ADMIN_PASSWORD})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "CAM_IT_DB_URL", matches = ".+")
class DataReflectionIntegrationTest {
    static final String ADMIN_EMAIL = "it-admin@college.test";
    static final String ADMIN_PASSWORD = "Integration-Admin-123!";
    private static final String PASSWORD = "Integration-User-123!";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getenv("CAM_IT_DB_URL"));
        r.add("spring.datasource.username", () -> System.getenv().getOrDefault("CAM_IT_DB_USER", "root"));
        r.add("spring.datasource.password", () -> System.getenv().getOrDefault("CAM_IT_DB_PASSWORD", ""));
    }

    /** Start every test run from an empty schema. */
    @TestConfiguration
    static class CleanDatabase {
        @Bean FlywayMigrationStrategy cleanThenMigrate() { return flyway -> { flyway.clean(); flyway.migrate(); }; }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private Actor admin;
    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void signInAdmin() throws Exception { admin = login(ADMIN_EMAIL, ADMIN_PASSWORD); }

    // ---------------------------------------------------------------------------------------------

    @Test
    void attendanceSavedByFacultyIsReflectedForStudentsAdminAndReports() throws Exception {
        Fixture f = newClass();
        Actor faculty = f.faculty, s1 = f.students.get(0), s2 = f.students.get(1);

        // Admin and faculty both see the class with its roster size; the student sees the class and its teacher.
        JsonNode adminClass = find(admin.get("/api/admin/classes"), "sectionId", f.sectionId);
        assertThat(adminClass.get("students").asInt()).isEqualTo(2);
        assertThat(adminClass.get("faculty").asText()).isEqualTo(f.facultyName);
        assertThat(find(faculty.get("/api/faculty/classes"), "sectionId", f.sectionId).get("students").asInt()).isEqualTo(2);
        assertThat(find(s1.get("/api/student/classes"), "sectionId", f.sectionId).get("faculty").asText()).isEqualTo(f.facultyName);
        assertThat(faculty.get("/api/faculty/classes/" + f.assignmentId + "/roster")).hasSize(2);

        long session = openSession(faculty, f, today.minusDays(1), "Lecture 1");
        save(faculty, session, 0, Map.of(s1.id, "PRESENT", s2.id, "ABSENT"));

        assertThat(s1.get("/api/student/attendance/summary").get("percentage").asDouble()).isEqualTo(100.0);
        assertThat(s2.get("/api/student/attendance/summary").get("percentage").asDouble()).isEqualTo(0.0);
        assertThat(statusIn(admin.get("/api/reports/attendance?sectionId=" + f.sectionId), s2.email)).isEqualTo("ABSENT");
        assertThat(faculty.get("/api/reports/attendance?sectionId=" + f.sectionId)).hasSize(2);

        // Faculty corrects a mark: every view updates and the percentage formula treats LATE as attended.
        save(faculty, session, 1, Map.of(s1.id, "PRESENT", s2.id, "LATE"));
        JsonNode s2Summary = s2.get("/api/student/attendance/summary");
        assertThat(s2Summary.get("late").asInt()).isEqualTo(1);
        assertThat(s2Summary.get("percentage").asDouble()).isEqualTo(100.0);
        assertThat(s2.get("/api/student/attendance/history").get("content").get(0).get("status").asText()).isEqualTo("LATE");
        assertThat(statusIn(admin.get("/api/reports/attendance?sectionId=" + f.sectionId), s2.email)).isEqualTo("LATE");
        JsonNode summaryRow = find(admin.get("/api/reports/summary?sectionId=" + f.sectionId), "email", s2.email);
        assertThat(summaryRow.get("percentage").asDouble()).isEqualTo(100.0);
        assertThat(admin.raw(MockMvcRequestBuilders.get("/api/reports/attendance.csv?sectionId=" + f.sectionId), 200).getContentAsString())
            .contains(s2.email).contains("LATE");

        // A second session changes the student percentage consistently across views.
        long second = openSession(faculty, f, today, "Lecture 2");
        save(faculty, second, 0, Map.of(s1.id, "ABSENT", s2.id, "PRESENT"));
        assertThat(s1.get("/api/student/attendance/summary").get("percentage").asDouble()).isEqualTo(50.0);
        assertThat(find(admin.get("/api/reports/summary?sectionId=" + f.sectionId), "email", s1.email).get("percentage").asDouble()).isEqualTo(50.0);
        assertThat(find(faculty.get("/api/faculty/classes"), "sectionId", f.sectionId).get("completedSessions").asInt()).isEqualTo(2);
    }

    @Test
    void deactivatingAnyPartOfAClassHidesItFromEveryRoleAndReactivationRestoresIt() throws Exception {
        Fixture f = newClass();
        Actor s1 = f.students.get(0);
        long session = openSession(f.faculty, f, today.minusDays(1), "Lecture 1");
        save(f.faculty, session, 0, Map.of(s1.id, "PRESENT", f.students.get(1).id, "PRESENT"));

        Map<String, Long> parts = Map.of("subjects", f.subjectId, "sections", f.sectionId, "courses", f.courseId, "departments", f.departmentId, "semesters", f.semesterId);
        for (var part : parts.entrySet()) {
            String path = "/api/admin/manage/" + part.getKey() + "/" + part.getValue();
            String value = find(admin.get("/api/admin/manage/" + part.getKey()), "id", part.getValue()).get("value").asText();
            admin.put(path, Map.of("value", value, "active", false), 200);

            assertThat(ids(admin.get("/api/admin/classes"), "sectionId")).as(part.getKey() + " hidden from admin").doesNotContain(f.sectionId);
            assertThat(ids(f.faculty.get("/api/faculty/classes"), "sectionId")).as(part.getKey() + " hidden from faculty").doesNotContain(f.sectionId);
            assertThat(ids(s1.get("/api/student/classes"), "sectionId")).as(part.getKey() + " hidden from student").doesNotContain(f.sectionId);
            f.faculty.post("/api/faculty/classes/" + f.assignmentId + "/sessions", Map.of("sessionDate", today.toString(), "sessionLabel", "Blocked"), 403);
            // Saved attendance is history and stays visible to the student.
            assertThat(s1.get("/api/student/attendance/summary").get("total").asInt()).isEqualTo(1);

            admin.put(path, Map.of("value", value, "active", true), 200);
            assertThat(ids(f.faculty.get("/api/faculty/classes"), "sectionId")).as(part.getKey() + " restored").contains(f.sectionId);
        }

        // Re-running class setup with the same codes reactivates a deactivated subject.
        admin.put("/api/admin/manage/subjects/" + f.subjectId, Map.of("value", "Subject " + f.code, "active", false), 200);
        JsonNode again = admin.post("/api/admin/academic/setup", f.setup, 200);
        assertThat(again.get("subjectId").asLong()).isEqualTo(f.subjectId);
        assertThat(ids(f.faculty.get("/api/faculty/classes"), "sectionId")).contains(f.sectionId);
    }

    @Test
    void enrollmentAndAssignmentChangesAreReflectedAndCanBeReactivated() throws Exception {
        Fixture f = newClass();
        Actor s1 = f.students.get(0), s2 = f.students.get(1);
        long enrollment = find(admin.get("/api/admin/manage/student_enrollments"), "label", s2.name + " · " + f.code).get("id").asLong();

        admin.put("/api/admin/manage/student_enrollments/" + enrollment, Map.of("value", "x", "active", false), 200);
        assertThat(ids(f.faculty.get("/api/faculty/classes/" + f.assignmentId + "/roster"), "studentId")).containsExactly(s1.id);
        assertThat(s2.get("/api/student/classes")).isEmpty();
        long session = openSession(f.faculty, f, today, "Lecture 1");
        f.faculty.put("/api/faculty/sessions/" + session + "/attendance", attendance(0, Map.of(s1.id, "PRESENT", s2.id, "PRESENT")), 400);
        save(f.faculty, session, 0, Map.of(s1.id, "PRESENT"));

        // Enrolling again reactivates instead of failing with a duplicate error.
        assertThat(admin.post("/api/admin/enrollments", Map.of("studentId", s2.id, "sectionId", f.sectionId), 200).get("status").asText()).isEqualTo("reactivated");
        admin.post("/api/admin/enrollments", Map.of("studentId", s2.id, "sectionId", f.sectionId), 409);
        assertThat(f.faculty.get("/api/faculty/classes/" + f.assignmentId + "/roster")).hasSize(2);
        assertThat(s2.get("/api/student/classes")).hasSize(1);

        // Removing the faculty assignment removes the class and its reports from that faculty only.
        admin.put("/api/admin/manage/faculty_assignments/" + f.assignmentId, Map.of("value", "x", "active", false), 200);
        assertThat(f.faculty.get("/api/faculty/classes")).isEmpty();
        assertThat(f.faculty.get("/api/reports/attendance")).isEmpty();
        f.faculty.get("/api/faculty/sessions/" + session, 403);
        assertThat(admin.get("/api/reports/attendance?sectionId=" + f.sectionId)).hasSize(1);
        assertThat(absent(find(admin.get("/api/admin/classes"), "sectionId", f.sectionId), "faculty")).isTrue();

        JsonNode reassigned = admin.post("/api/admin/assignments", Map.of("userId", f.facultyId, "sectionId", f.sectionId, "subjectId", f.subjectId), 200);
        assertThat(reassigned.get("status").asText()).isEqualTo("reactivated");
        assertThat(f.faculty.get("/api/faculty/classes")).hasSize(1);
        assertThat(f.faculty.get("/api/reports/attendance")).hasSize(1);
    }

    @Test
    void cancelledSessionsLeaveEveryTotalAndCanBeReopened() throws Exception {
        Fixture f = newClass();
        Actor s1 = f.students.get(0), s2 = f.students.get(1);
        long session = openSession(f.faculty, f, today, "Lecture 1");
        save(f.faculty, session, 0, Map.of(s1.id, "ABSENT", s2.id, "PRESENT"));
        assertThat(s1.get("/api/student/attendance/summary").get("total").asInt()).isEqualTo(1);

        f.faculty.post("/api/faculty/sessions/" + session + "/cancel", Map.of(), 200);
        assertThat(s1.get("/api/student/attendance/summary").get("total").asInt()).isZero();
        assertThat(absent(s1.get("/api/student/attendance/summary"), "percentage")).isTrue();
        assertThat(admin.get("/api/reports/attendance?sectionId=" + f.sectionId)).isEmpty();
        f.faculty.put("/api/faculty/sessions/" + session + "/attendance", attendance(2, Map.of(s1.id, "PRESENT", s2.id, "PRESENT")), 409);

        JsonNode reopened = f.faculty.post("/api/faculty/classes/" + f.assignmentId + "/sessions", Map.of("sessionDate", today.toString(), "sessionLabel", "Lecture 1"), 200);
        assertThat(reopened.get("status").asText()).isEqualTo("reopened");
        assertThat(reopened.get("id").asLong()).isEqualTo(session);
        long version = f.faculty.get("/api/faculty/sessions/" + session).get("version").asLong();
        save(f.faculty, session, version, Map.of(s1.id, "PRESENT", s2.id, "PRESENT"));
        assertThat(s1.get("/api/student/attendance/summary").get("percentage").asDouble()).isEqualTo(100.0);
    }

    @Test
    void twoFacultyOnOneClassSeeEachOthersMarksAndStaleSavesAreRejected() throws Exception {
        Fixture f = newClass();
        Actor other = newUser("FACULTY");
        admin.post("/api/admin/assignments", Map.of("userId", other.id, "sectionId", f.sectionId, "subjectId", f.subjectId), 200);
        long otherAssignment = find(other.get("/api/faculty/classes"), "sectionId", f.sectionId).get("assignmentId").asLong();
        Actor s1 = f.students.get(0), s2 = f.students.get(1);

        long session = openSession(f.faculty, f, today, "Lecture 1");
        JsonNode same = other.post("/api/faculty/classes/" + otherAssignment + "/sessions", Map.of("sessionDate", today.toString(), "sessionLabel", "Lecture 1"), 200);
        assertThat(same.get("status").asText()).isEqualTo("existing");
        assertThat(same.get("id").asLong()).isEqualTo(session);

        save(f.faculty, session, 0, Map.of(s1.id, "PRESENT", s2.id, "ABSENT"));
        JsonNode seen = other.get("/api/faculty/sessions/" + session);
        assertThat(seen.get("version").asLong()).isEqualTo(1);
        assertThat(statusOf(seen.get("attendance"), s2.id)).isEqualTo("ABSENT");
        // The other faculty member loaded version 0 before the save: their save must not overwrite silently.
        other.put("/api/faculty/sessions/" + session + "/attendance", attendance(0, Map.of(s1.id, "LATE", s2.id, "LATE")), 409);
        assertThat(s2.get("/api/student/attendance/summary").get("absent").asInt()).isEqualTo(1);
        assertThat(find(admin.get("/api/admin/classes"), "sectionId", f.sectionId).get("faculty").asText()).contains(f.facultyName).contains(other.name);
    }

    @Test
    void everyRoleSeesOnlyItsOwnData() throws Exception {
        Fixture f = newClass();
        Fixture g = newClass();
        Actor s1 = f.students.get(0);
        long session = openSession(f.faculty, f, today, "Lecture 1");
        save(f.faculty, session, 0, Map.of(s1.id, "PRESENT", f.students.get(1).id, "ABSENT"));

        new Actor(new MockHttpSession(), 0, "", "").get("/api/student/attendance/summary", 401);
        s1.get("/api/admin/users", 403);
        s1.get("/api/faculty/classes", 403);
        s1.get("/api/reports/attendance", 403);
        f.faculty.get("/api/admin/users", 403);
        // Faculty of another class cannot read or change this roster, session or report rows.
        g.faculty.get("/api/faculty/classes/" + f.assignmentId + "/roster", 403);
        g.faculty.get("/api/faculty/sessions/" + session, 403);
        g.faculty.put("/api/faculty/sessions/" + session + "/attendance", attendance(1, Map.of(s1.id, "ABSENT")), 403);
        assertThat(g.faculty.get("/api/reports/attendance?sectionId=" + f.sectionId)).isEmpty();
        // Students in another class see none of it.
        Actor other = g.students.get(0);
        assertThat(other.get("/api/student/attendance/summary").get("total").asInt()).isZero();
        assertThat(other.get("/api/student/attendance/history").get("totalElements").asInt()).isZero();
        assertThat(other.get("/api/student/classes")).allSatisfy(c -> assertThat(c.get("sectionId").asLong()).isEqualTo(g.sectionId));
    }

    @Test
    void accountChangesTakeEffectImmediately() throws Exception {
        Fixture f = newClass();
        Actor s1 = f.students.get(0);

        // Renaming is reflected in the faculty roster and the student's own views.
        admin.put("/api/admin/manage/users/" + s1.id, Map.of("value", "Renamed " + f.code, "active", true), 200);
        assertThat(find(f.faculty.get("/api/faculty/classes/" + f.assignmentId + "/roster"), "studentId", s1.id).get("displayName").asText()).isEqualTo("Renamed " + f.code);
        assertThat(s1.get("/api/auth/me").get("displayName").asText()).isEqualTo("Renamed " + f.code);
        admin.put("/api/admin/manage/subjects/" + f.subjectId, Map.of("value", "Renamed subject", "active", true), 200);
        assertThat(find(s1.get("/api/student/classes"), "sectionId", f.sectionId).get("subjectName").asText()).isEqualTo("Renamed subject");

        // Deactivating a user ends their live session and removes them from rosters.
        admin.put("/api/admin/manage/users/" + s1.id, Map.of("value", "Renamed " + f.code, "active", false), 200);
        s1.get("/api/student/attendance/summary", 401);
        assertThat(f.faculty.get("/api/faculty/classes/" + f.assignmentId + "/roster")).hasSize(1);
        assertThat(find(admin.get("/api/admin/classes"), "sectionId", f.sectionId).get("students").asInt()).isEqualTo(1);
        anonymousLogin(Map.of("email", s1.email, "password", PASSWORD), 401);
        admin.put("/api/admin/manage/users/" + s1.id, Map.of("value", "Renamed " + f.code, "active", true), 200);

        // A password reset forces the user to pick a new password before anything else.
        admin.post("/api/admin/users/" + s1.id + "/password", Map.of("password", "Reset-By-Admin-123!"), 200);
        Actor reset = login(s1.email, "Reset-By-Admin-123!");
        assertThat(reset.get("/api/auth/me").get("mustChangePassword").asBoolean()).isTrue();
        reset.get("/api/student/attendance/summary", 403);
        reset.post("/api/auth/password", Map.of("currentPassword", "Reset-By-Admin-123!", "newPassword", PASSWORD), 200);
        reset.get("/api/student/attendance/summary", 200);

        // The last administrator can never be disabled.
        long adminId = admin.get("/api/auth/me").get("id").asLong();
        admin.put("/api/admin/manage/users/" + adminId, Map.of("value", "Campus Administrator", "active", false), 400);
        admin.post("/api/admin/users/" + adminId + "/deactivate", Map.of(), 400);
        admin.get("/api/admin/users", 200);
    }

    @Test
    void invalidInputReturnsReadableErrorsInsteadOfServerErrors() throws Exception {
        Fixture f = newClass();
        String sessions = "/api/faculty/classes/" + f.assignmentId + "/sessions";
        assertThat(f.faculty.post(sessions, Map.of("sessionDate", today.toString(), "sessionLabel", "x".repeat(81)), 400).get("error").asText()).contains("sessionLabel");
        assertThat(f.faculty.post(sessions, Map.of("sessionDate", "2026-13-45", "sessionLabel", "Bad"), 400).get("error").asText()).contains("YYYY-MM-DD");
        assertThat(f.faculty.post(sessions, Map.of("sessionDate", today.minusYears(3).toString(), "sessionLabel", "Old"), 400).get("error").asText()).contains("semester");
        assertThat(f.faculty.post(sessions, Map.of("sessionDate", today.plusDays(10).toString(), "sessionLabel", "Future"), 400).get("error").asText()).contains("future");
        assertThat(admin.post("/api/admin/enrollments", Map.of("studentId", f.students.get(0).id, "sectionId", 999999), 400).get("error").asText()).contains("active section");
        assertThat(admin.get("/api/reports/attendance?from=2026-99-01", 400).get("error").asText()).contains("YYYY-MM-DD");
        assertThat(admin.get("/api/reports/attendance?from=2026-02-01&to=2026-01-01", 400).get("error").asText()).contains("From date");
        assertThat(admin.get("/api/reports/attendance?sectionId=abc", 400).get("error").asText()).contains("sectionId");
        assertThat(admin.get("/api/admin/manage/audit_events", 400).get("error").asText()).contains("Unknown");
        String malformed = admin.raw(MockMvcRequestBuilders.post("/api/admin/users").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{bad"), 400).getContentAsString();
        assertThat(json.readTree(malformed).get("error").asText()).contains("malformed");
        assertThat(admin.post("/api/admin/users", Map.of("displayName", "Dup", "email", f.students.get(0).email, "role", "STUDENT", "password", PASSWORD), 409).has("error")).isTrue();
        assertThat(admin.get("/api/no-such-route", 404).has("error")).isTrue();
        admin.raw(MockMvcRequestBuilders.post("/api/admin/users").contentType(MediaType.APPLICATION_JSON).content("{}"), 403); // no CSRF token
    }

    @Test
    void repeatedWrongPasswordsAreThrottled() throws Exception {
        Actor user = newUser("STUDENT");
        Map<String, String> wrong = Map.of("email", user.email, "password", "definitely-wrong-password");
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) anonymousLogin(wrong, 401);
        anonymousLogin(Map.of("email", user.email, "password", PASSWORD), 429);
    }

    // ---- fixtures and helpers -------------------------------------------------------------------

    private record Fixture(String code, Map<String, Object> setup, long departmentId, long semesterId, long courseId, long sectionId, long subjectId,
                           long facultyId, String facultyName, long assignmentId, Actor faculty, List<Actor> students) {}

    /** Creates a class with one faculty member and two enrolled students, all signed in with their own sessions. */
    private Fixture newClass() throws Exception {
        String code = "IT" + SEQ.incrementAndGet() + "X" + System.nanoTime() % 100_000;
        Map<String, Object> setup = Map.ofEntries(
            Map.entry("departmentCode", "D" + code), Map.entry("departmentName", "Dept " + code),
            Map.entry("yearLabel", "Y" + code), Map.entry("yearStart", today.minusMonths(6).toString()), Map.entry("yearEnd", today.plusMonths(6).toString()),
            Map.entry("semester", "Semester " + code), Map.entry("semesterStart", today.minusMonths(2).toString()), Map.entry("semesterEnd", today.plusMonths(2).toString()),
            Map.entry("courseCode", "C" + code), Map.entry("courseName", "Course " + code), Map.entry("sectionCode", code),
            Map.entry("subjectCode", "S" + code), Map.entry("subjectName", "Subject " + code));
        JsonNode created = admin.post("/api/admin/academic/setup", setup, 200);
        long sectionId = created.get("sectionId").asLong(), subjectId = created.get("subjectId").asLong();
        long departmentId = find(admin.get("/api/admin/manage/departments"), "value", "Dept " + code).get("id").asLong();
        long courseId = find(admin.get("/api/admin/manage/courses"), "value", "Course " + code).get("id").asLong();
        long semesterId = find(admin.get("/api/admin/manage/semesters"), "value", "Semester " + code).get("id").asLong();

        Actor faculty = newUser("FACULTY");
        admin.post("/api/admin/assignments", Map.of("userId", faculty.id, "sectionId", sectionId, "subjectId", subjectId), 200);
        List<Actor> students = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Actor s = newUser("STUDENT");
            admin.post("/api/admin/enrollments", Map.of("studentId", s.id, "sectionId", sectionId), 200);
            students.add(s);
        }
        long assignmentId = find(faculty.get("/api/faculty/classes"), "sectionId", sectionId).get("assignmentId").asLong();
        return new Fixture(code, setup, departmentId, semesterId, courseId, sectionId, subjectId, faculty.id, faculty.name, assignmentId, faculty, students);
    }

    /** Creates an account (temporary password), signs in, and replaces the temporary password as a real user must. */
    private Actor newUser(String role) throws Exception {
        int n = SEQ.incrementAndGet();
        String email = role.toLowerCase() + n + "-" + System.nanoTime() + "@it.college.test";
        String name = role.charAt(0) + role.substring(1).toLowerCase() + " " + n;
        admin.post("/api/admin/users", Map.of("displayName", name, "email", email, "role", role, "password", "Temporary-Pass-123!"), 200);
        Actor user = login(email, "Temporary-Pass-123!");
        user.get("/api/auth/me", 200);
        user.get(role.equals("STUDENT") ? "/api/student/classes" : "/api/faculty/classes", 403); // blocked until the temporary password is replaced
        user.post("/api/auth/password", Map.of("currentPassword", "Temporary-Pass-123!", "newPassword", PASSWORD), 200);
        return user;
    }

    private Actor login(String email, String password) throws Exception {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletResponse r = mvc.perform(MockMvcRequestBuilders.post("/api/auth/login").session(session).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email", email, "password", password)))).andReturn().getResponse();
        assertThat(r.getStatus()).as("login " + email + ": " + r.getContentAsString()).isEqualTo(200);
        JsonNode me = json.readTree(r.getContentAsString());
        return new Actor(session, me.get("id").asLong(), email, me.get("displayName").asText());
    }

    private void anonymousLogin(Map<String, String> body, int status) throws Exception {
        MockHttpServletResponse r = mvc.perform(MockMvcRequestBuilders.post("/api/auth/login").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn().getResponse();
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);
    }

    private long openSession(Actor faculty, Fixture f, LocalDate date, String label) throws Exception {
        return faculty.post("/api/faculty/classes/" + f.assignmentId + "/sessions", Map.of("sessionDate", date.toString(), "sessionLabel", label), 200).get("id").asLong();
    }

    private void save(Actor faculty, long session, long expectedVersion, Map<Long, String> marks) throws Exception {
        faculty.put("/api/faculty/sessions/" + session + "/attendance", attendance(expectedVersion, marks), 200);
    }

    private static Map<String, Object> attendance(long expectedVersion, Map<Long, String> marks) {
        return Map.of("expectedVersion", expectedVersion, "records", marks.entrySet().stream().map(e -> Map.of("studentId", e.getKey(), "status", e.getValue())).toList());
    }

    private static JsonNode find(JsonNode array, String field, Object value) {
        return StreamSupport.stream(array.spliterator(), false).filter(n -> n.get(field).asText().equals(String.valueOf(value))).findFirst()
            .orElseThrow(() -> new AssertionError("No row with " + field + "=" + value + " in " + array));
    }

    private static List<Long> ids(JsonNode array, String field) {
        return StreamSupport.stream(array.spliterator(), false).map(n -> n.get(field).asLong()).toList();
    }

    /** The API omits null fields (spring.jackson.default-property-inclusion=non_null). */
    private static boolean absent(JsonNode node, String field) { return node.path(field).isMissingNode() || node.path(field).isNull(); }

    private static String statusIn(JsonNode rows, String email) { return find(rows, "email", email).get("status").asText(); }
    private static String statusOf(JsonNode marks, long student) { return find(marks, "studentId", student).get("status").asText(); }

    /** One signed-in browser: its own session cookie, with a CSRF token on writes. */
    private class Actor {
        final MockHttpSession session; final long id; final String email; final String name;
        Actor(MockHttpSession session, long id, String email, String name) { this.session = session; this.id = id; this.email = email; this.name = name; }

        JsonNode get(String path) throws Exception { return get(path, 200); }
        JsonNode get(String path, int status) throws Exception { return body(raw(MockMvcRequestBuilders.get(path), status)); }
        JsonNode post(String path, Object body, int status) throws Exception {
            return body(raw(MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)).with(csrf()), status));
        }
        JsonNode put(String path, Object body, int status) throws Exception {
            return body(raw(MockMvcRequestBuilders.put(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)).with(csrf()), status));
        }

        MockHttpServletResponse raw(MockHttpServletRequestBuilder request, int status) throws Exception {
            MockHttpServletResponse r = mvc.perform(request.session(session)).andReturn().getResponse();
            assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);
            return r;
        }

        private JsonNode body(MockHttpServletResponse r) throws Exception {
            String text = r.getContentAsString();
            return text.isBlank() ? json.nullNode() : json.readTree(text);
        }
    }
}
