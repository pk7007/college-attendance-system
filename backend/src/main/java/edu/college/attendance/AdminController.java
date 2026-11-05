package edu.college.attendance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.sql.Date;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;
    public AdminController(JdbcTemplate db,PasswordEncoder passwords) { this.db=db; this.passwords=passwords; }

    public record NewUser(@NotBlank @Size(max=120) String displayName,@Email @NotBlank String email,@NotBlank String role,@NotBlank @Size(min=12,max=100) String password) {}
    public record AcademicSetup(@NotBlank String departmentCode,@NotBlank String departmentName,@NotBlank String yearLabel,@NotBlank String yearStart,@NotBlank String yearEnd,@NotBlank String semester,@NotBlank String semesterStart,@NotBlank String semesterEnd,@NotBlank String courseCode,@NotBlank String courseName,@NotBlank String sectionCode,@NotBlank String subjectCode,@NotBlank String subjectName) {}
    public record Link(long userId,long sectionId,long subjectId) {}
    public record Enrollment(long studentId,long sectionId) {}

    @GetMapping("/users") List<Map<String,Object>> users() { return db.queryForList("SELECT id,email,display_name displayName,role,active,created_at createdAt FROM users ORDER BY display_name"); }
    @PostMapping("/users") @Transactional Map<String,Object> createUser(@Valid @RequestBody NewUser input,org.springframework.security.core.Authentication auth) {
        String role=input.role().toUpperCase(); if (!List.of("ADMIN","FACULTY","STUDENT").contains(role)) throw new IllegalArgumentException("Role must be ADMIN, FACULTY, or STUDENT.");
        String email=input.email().trim().toLowerCase();
        db.update("INSERT INTO users(email,password_hash,display_name,role,must_change_password) VALUES(?,?,?,?,TRUE)",email,passwords.encode(input.password()),input.displayName().trim(),role);
        Map<String,Object> created=db.queryForMap("SELECT id,email,display_name displayName,role,active,must_change_password mustChangePassword FROM users WHERE email=?",email);
        audit(auth,"USER_CREATED","USER",((Number)created.get("id")).longValue()); return created;
    }
    @PostMapping("/users/{id}/deactivate") @Transactional Map<String,Object> deactivate(@PathVariable long id,org.springframework.security.core.Authentication auth) {
        Integer admin=db.queryForObject("SELECT COUNT(*) FROM users WHERE role='ADMIN' AND active=TRUE",Integer.class); Integer target=db.queryForObject("SELECT COUNT(*) FROM users WHERE id=? AND role='ADMIN' AND active=TRUE",Integer.class,id);
        if(target!=null&&target>0&&admin!=null&&admin<=1) throw new IllegalArgumentException("The last active administrator cannot be deactivated.");
        int count=db.update("UPDATE users SET active=FALSE WHERE id=?",id); if(count==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found.");
        audit(auth,"USER_DEACTIVATED","USER",id);
        return Map.of("id",id,"active",false);
    }
    @GetMapping("/classes") List<Map<String,Object>> classes() { return db.queryForList("SELECT s.id sectionId,c.name courseName,s.code sectionCode,se.label semester,sub.id subjectId,sub.code subjectCode,sub.name subjectName FROM sections s JOIN courses c ON c.id=s.course_id JOIN semesters se ON se.id=s.semester_id JOIN subjects sub ON sub.course_id=c.id WHERE s.active=TRUE AND c.active=TRUE AND sub.active=TRUE ORDER BY c.name,s.code,sub.name"); }

    @PostMapping("/academic/setup") @Transactional Map<String,Object> setup(@Valid @RequestBody AcademicSetup a,org.springframework.security.core.Authentication auth) {
        String departmentCode=a.departmentCode().trim().toUpperCase(); Long department=findId("SELECT id FROM departments WHERE code=?",departmentCode); if(department==null){db.update("INSERT INTO departments(code,name) VALUES(?,?)",departmentCode,a.departmentName().trim());department=findId("SELECT id FROM departments WHERE code=?",departmentCode);}
        Date ys=Date.valueOf(a.yearStart()),ye=Date.valueOf(a.yearEnd()),ss=Date.valueOf(a.semesterStart()),se=Date.valueOf(a.semesterEnd());
        if(ye.before(ys)||ss.before(ys)||se.after(ye)||se.before(ss)) throw new IllegalArgumentException("Semester dates must fit within the academic year.");
        Long year=findId("SELECT id FROM academic_years WHERE label=?",a.yearLabel()); if(year==null){db.update("INSERT INTO academic_years(label,start_date,end_date) VALUES(?,?,?)",a.yearLabel(),ys,ye);year=findId("SELECT id FROM academic_years WHERE label=?",a.yearLabel());}
        Map<String,Object> yearDates=db.queryForMap("SELECT start_date startDate,end_date endDate FROM academic_years WHERE id=?",year);
        if(ss.before((Date)yearDates.get("startDate"))||se.after((Date)yearDates.get("endDate"))) throw new IllegalArgumentException("Semester dates must fit within the selected academic year.");
        Long semester=findId("SELECT id FROM semesters WHERE academic_year_id=? AND label=?",year,a.semester()); if(semester==null){db.update("INSERT INTO semesters(academic_year_id,label,start_date,end_date) VALUES(?,?,?,?)",year,a.semester(),ss,se);semester=findId("SELECT id FROM semesters WHERE academic_year_id=? AND label=?",year,a.semester());}
        String courseCode=a.courseCode().trim().toUpperCase(); Long course=findId("SELECT id FROM courses WHERE department_id=? AND code=?",department,courseCode); if(course==null){db.update("INSERT INTO courses(department_id,code,name) VALUES(?,?,?)",department,courseCode,a.courseName().trim());course=findId("SELECT id FROM courses WHERE department_id=? AND code=?",department,courseCode);}
        String sectionCode=a.sectionCode().trim().toUpperCase(); Long section=findId("SELECT id FROM sections WHERE course_id=? AND semester_id=? AND code=?",course,semester,sectionCode); if(section==null){db.update("INSERT INTO sections(course_id,semester_id,code) VALUES(?,?,?)",course,semester,sectionCode);section=findId("SELECT id FROM sections WHERE course_id=? AND semester_id=? AND code=?",course,semester,sectionCode);}
        String subjectCode=a.subjectCode().trim().toUpperCase(); Long subject=findId("SELECT id FROM subjects WHERE course_id=? AND code=?",course,subjectCode); if(subject==null){db.update("INSERT INTO subjects(course_id,code,name) VALUES(?,?,?)",course,subjectCode,a.subjectName().trim());subject=findId("SELECT id FROM subjects WHERE course_id=? AND code=?",course,subjectCode);}
        audit(auth,"ACADEMIC_CLASS_CREATED","SECTION",section);
        return Map.of("sectionId",section,"subjectId",subject,"message","Academic class created.");
    }
    @PostMapping("/enrollments") @Transactional Map<String,Object> enroll(@Valid @RequestBody Enrollment e,org.springframework.security.core.Authentication auth) {
        requireRole(e.studentId(),"STUDENT"); db.update("INSERT INTO student_enrollments(student_user_id,section_id) VALUES(?,?)",e.studentId(),e.sectionId()); audit(auth,"STUDENT_ENROLLED","SECTION",e.sectionId()); return Map.of("studentId",e.studentId(),"sectionId",e.sectionId());
    }
    @PostMapping("/assignments") @Transactional Map<String,Object> assign(@Valid @RequestBody Link l,org.springframework.security.core.Authentication auth) {
        requireRole(l.userId(),"FACULTY");
        Integer compatible=db.queryForObject("SELECT COUNT(*) FROM sections s JOIN subjects sub ON sub.course_id=s.course_id WHERE s.id=? AND sub.id=? AND s.active=TRUE AND sub.active=TRUE",Integer.class,l.sectionId(),l.subjectId());
        if(compatible==null||compatible==0) throw new IllegalArgumentException("Subject must belong to the section's course."); db.update("INSERT INTO faculty_assignments(faculty_user_id,section_id,subject_id) VALUES(?,?,?)",l.userId(),l.sectionId(),l.subjectId()); audit(auth,"FACULTY_ASSIGNED","SECTION",l.sectionId()); return Map.of("facultyId",l.userId(),"sectionId",l.sectionId(),"subjectId",l.subjectId());
    }
    @GetMapping("/enrollments") List<Map<String,Object>> enrollments() { return db.queryForList("SELECT e.id,e.student_user_id studentId,u.display_name studentName,e.section_id sectionId,s.code sectionCode,e.status FROM student_enrollments e JOIN users u ON u.id=e.student_user_id JOIN sections s ON s.id=e.section_id ORDER BY s.code,u.display_name"); }
    private void requireRole(long id,String role) { Integer found=db.queryForObject("SELECT COUNT(*) FROM users WHERE id=? AND role=? AND active=TRUE",Integer.class,id,role); if(found==null||found==0) throw new IllegalArgumentException("Selected active user must have role "+role+"."); }
    private Long findId(String sql,Object... args) { List<Long> rows=db.query(sql,(rs,n)->rs.getLong(1),args); return rows.isEmpty()?null:rows.getFirst(); }
    private void audit(org.springframework.security.core.Authentication auth,String action,String type,long id) { Long actor=db.queryForObject("SELECT id FROM users WHERE email=?",Long.class,auth.getName()); db.update("INSERT INTO audit_events(actor_user_id,action,entity_type,entity_id) VALUES(?,?,?,?)",actor,action,type,id); }
}
