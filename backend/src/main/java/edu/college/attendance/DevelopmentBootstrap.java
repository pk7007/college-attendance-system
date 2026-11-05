package edu.college.attendance;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

@Configuration
@Profile("dev")
public class DevelopmentBootstrap {
    @Bean ApplicationRunner demoData(JdbcTemplate db,PasswordEncoder passwords,
      @Value("${CAM_BOOTSTRAP_ADMIN_EMAIL:}") String adminEmail,@Value("${CAM_BOOTSTRAP_ADMIN_PASSWORD:}") String adminPassword,
      @Value("${CAM_DEMO_FACULTY_PASSWORD:FacultyDemo-2026!}") String facultyPassword,@Value("${CAM_DEMO_STUDENT_PASSWORD:StudentDemo-2026!}") String studentPassword) {
        return args -> seed(db,passwords,adminEmail,adminPassword,facultyPassword,studentPassword);
    }
    @Transactional void seed(JdbcTemplate db,PasswordEncoder encoder,String adminEmail,String adminPassword,String facultyPassword,String studentPassword) {
        if(adminEmail==null||adminEmail.isBlank()||adminPassword==null||adminPassword.length()<12) return;
        addUser(db,encoder,adminEmail,"Campus Administrator","ADMIN",adminPassword);
        addUser(db,encoder,"faculty@college.test","Jordan Lee","FACULTY",facultyPassword);
        addUser(db,encoder,"student@college.test","Sam Patel","STUDENT",studentPassword);
        Integer exists=db.queryForObject("SELECT COUNT(*) FROM departments WHERE code='CS'",Integer.class); if(exists!=null&&exists>0)return;
        db.update("INSERT INTO departments(code,name) VALUES('CS','Computer Science')");
        db.update("INSERT INTO academic_years(label,start_date,end_date) VALUES('2026-2027','2026-06-01','2027-05-31')");
        db.update("INSERT INTO semesters(academic_year_id,label,start_date,end_date) SELECT id,'Semester 1','2026-06-01','2026-12-31' FROM academic_years WHERE label='2026-2027'");
        db.update("INSERT INTO courses(department_id,code,name) SELECT id,'BSC-CS','B.Sc. Computer Science' FROM departments WHERE code='CS'");
        db.update("INSERT INTO sections(course_id,semester_id,code) SELECT c.id,se.id,'CS-2A' FROM courses c CROSS JOIN semesters se WHERE c.code='BSC-CS' AND se.label='Semester 1'");
        db.update("INSERT INTO subjects(course_id,code,name) SELECT id,'CS201','Data Structures' FROM courses WHERE code='BSC-CS'");
        db.update("INSERT INTO faculty_assignments(faculty_user_id,section_id,subject_id) SELECT f.id,s.id,sub.id FROM users f CROSS JOIN sections s CROSS JOIN subjects sub WHERE f.email='faculty@college.test' AND s.code='CS-2A' AND sub.code='CS201'");
        db.update("INSERT INTO student_enrollments(student_user_id,section_id) SELECT u.id,s.id FROM users u CROSS JOIN sections s WHERE u.email='student@college.test' AND s.code='CS-2A'");
        db.update("INSERT INTO class_sessions(section_id,subject_id,session_date,session_label,state,created_by) SELECT s.id,sub.id,'2026-11-03','Lecture 1','COMPLETED',f.id FROM sections s JOIN subjects sub ON sub.course_id=s.course_id JOIN users f ON f.email='faculty@college.test' WHERE s.code='CS-2A' AND sub.code='CS201'");
        db.update("INSERT INTO attendance_records(class_session_id,student_user_id,status,marked_by) SELECT cs.id,stu.id,'PRESENT',f.id FROM class_sessions cs JOIN users stu ON stu.email='student@college.test' JOIN users f ON f.email='faculty@college.test' WHERE cs.session_label='Lecture 1' AND cs.session_date='2026-11-03'");
        db.update("INSERT INTO class_sessions(section_id,subject_id,session_date,session_label,state,created_by) SELECT s.id,sub.id,'2026-11-04','Lecture 2','COMPLETED',f.id FROM sections s JOIN subjects sub ON sub.course_id=s.course_id JOIN users f ON f.email='faculty@college.test' WHERE s.code='CS-2A' AND sub.code='CS201'");
        db.update("INSERT INTO attendance_records(class_session_id,student_user_id,status,marked_by) SELECT cs.id,stu.id,'LATE',f.id FROM class_sessions cs JOIN users stu ON stu.email='student@college.test' JOIN users f ON f.email='faculty@college.test' WHERE cs.session_label='Lecture 2' AND cs.session_date='2026-11-04'");
    }
    private void addUser(JdbcTemplate db,PasswordEncoder encoder,String email,String name,String role,String password) {
        Integer exists=db.queryForObject("SELECT COUNT(*) FROM users WHERE email=?",Integer.class,email.toLowerCase());
        if(exists==null||exists==0) db.update("INSERT INTO users(email,password_hash,display_name,role,must_change_password) VALUES(?,?,?,?,FALSE)",email.toLowerCase(),encoder.encode(password),name,role);
    }
}
