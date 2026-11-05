package edu.college.attendance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotEmpty;
import java.time.LocalDate;
import java.sql.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
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
    public FacultyController(JdbcTemplate db) { this.db=db; }
    public record NewSession(@NotNull LocalDate sessionDate,@NotBlank String sessionLabel) {}
    public record Mark(long studentId,@NotBlank String status) {}
    public record Attendance(@NotNull Long expectedVersion,@NotEmpty List<@Valid Mark> records) {}

    @GetMapping("/classes") List<Map<String,Object>> classes(Authentication auth) { long uid=userId(auth); return db.queryForList("SELECT a.id assignmentId,s.id sectionId,sub.id subjectId,s.code sectionCode,c.name courseName,sub.code subjectCode,sub.name subjectName,se.label semester FROM faculty_assignments a JOIN sections s ON s.id=a.section_id JOIN courses c ON c.id=s.course_id JOIN semesters se ON se.id=s.semester_id JOIN subjects sub ON sub.id=a.subject_id WHERE a.faculty_user_id=? AND a.active=TRUE AND s.active=TRUE ORDER BY c.name,s.code,sub.name",uid); }
    @GetMapping("/classes/{assignmentId}/roster") List<Map<String,Object>> roster(@PathVariable long assignmentId,Authentication auth) { long section=assignment(assignmentId,userId(auth))[0]; return db.queryForList("SELECT u.id studentId,u.display_name displayName,u.email FROM student_enrollments e JOIN users u ON u.id=e.student_user_id WHERE e.section_id=? AND e.status='ACTIVE' AND u.active=TRUE ORDER BY u.display_name",section); }
    @GetMapping("/classes/{assignmentId}/sessions") List<Map<String,Object>> sessions(@PathVariable long assignmentId,Authentication auth) { long[] a=assignment(assignmentId,userId(auth)); return db.queryForList("SELECT id,session_date sessionDate,session_label sessionLabel,state,version FROM class_sessions WHERE section_id=? AND subject_id=? ORDER BY session_date DESC,id DESC",a[0],a[1]); }
    @PostMapping("/classes/{assignmentId}/sessions") Map<String,Object> createSession(@PathVariable long assignmentId,@Valid @RequestBody NewSession input,Authentication auth) {
        long[] a=assignment(assignmentId,userId(auth)); var found=db.query("SELECT id FROM class_sessions WHERE section_id=? AND subject_id=? AND session_date=? AND session_label=?",(rs,n)->rs.getLong(1),a[0],a[1],input.sessionDate(),input.sessionLabel().trim());
        if(!found.isEmpty()) return Map.of("id",found.getFirst(),"status","existing");
        Date date=Date.valueOf(input.sessionDate());
        db.update("INSERT INTO class_sessions(section_id,subject_id,session_date,session_label,created_by) VALUES(?,?,?,?,?)",a[0],a[1],date,input.sessionLabel().trim(),userId(auth));
        Long id=db.queryForObject("SELECT id FROM class_sessions WHERE section_id=? AND subject_id=? AND session_date=? AND session_label=?",Long.class,a[0],a[1],date,input.sessionLabel().trim());
        db.update("INSERT INTO audit_events(actor_user_id,action,entity_type,entity_id) VALUES(?,?,?,?)",userId(auth),"SESSION_OPENED","CLASS_SESSION",id);
        return Map.of("id",id,"status","created");
    }
    @GetMapping("/sessions/{sessionId}") Map<String,Object> session(@PathVariable long sessionId,Authentication auth) {
        long uid=userId(auth); Map<String,Object> s=sessionForFaculty(sessionId,uid); List<Map<String,Object>> marks=db.queryForList("SELECT student_user_id studentId,status FROM attendance_records WHERE class_session_id=? ORDER BY student_user_id",sessionId); s.put("attendance",marks); return s;
    }
    @PutMapping("/sessions/{sessionId}/attendance") @Transactional Map<String,Object> save(@PathVariable long sessionId,@Valid @RequestBody Attendance input,Authentication auth) {
        Map<String,Object> session=sessionForFaculty(sessionId,userId(auth)); if("CANCELLED".equals(session.get("state"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Cancelled sessions cannot be marked.");
        long version=((Number)session.get("version")).longValue(); if(version!=input.expectedVersion()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Session changed. Reload before saving.");
        long section=((Number)session.get("sectionId")).longValue(); List<Long> roster=db.query("SELECT e.student_user_id FROM student_enrollments e JOIN users u ON u.id=e.student_user_id WHERE e.section_id=? AND e.status='ACTIVE' AND u.active=TRUE",(rs,n)->rs.getLong(1),section);
        Map<Long,String> submitted=AttendanceRules.completeRoster(roster,input.records());
        for(Long student:roster) db.update("INSERT INTO attendance_records(class_session_id,student_user_id,status,marked_by) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE status=VALUES(status),marked_by=VALUES(marked_by),marked_at=CURRENT_TIMESTAMP(6)",sessionId,student,submitted.get(student),userId(auth));
        int changed=db.update("UPDATE class_sessions SET state='COMPLETED',version=version+1 WHERE id=? AND version=?",sessionId,version);
        if(changed!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Session changed while saving. Reload and review the current marks.");
        db.update("INSERT INTO audit_events(actor_user_id,action,entity_type,entity_id) VALUES(?,?,?,?)",userId(auth),"ATTENDANCE_SAVED","CLASS_SESSION",sessionId);
        return Map.of("sessionId",sessionId,"saved",submitted.size(),"state","COMPLETED");
    }
    @PostMapping("/sessions/{sessionId}/cancel") @Transactional Map<String,String> cancel(@PathVariable long sessionId,Authentication auth) { sessionForFaculty(sessionId,userId(auth)); db.update("UPDATE class_sessions SET state='CANCELLED',version=version+1 WHERE id=? AND state<>'CANCELLED'",sessionId); db.update("INSERT INTO audit_events(actor_user_id,action,entity_type,entity_id) VALUES(?,?,?,?)",userId(auth),"SESSION_CANCELLED","CLASS_SESSION",sessionId); return Map.of("state","CANCELLED"); }
    private long userId(Authentication auth) { Long id=db.queryForObject("SELECT id FROM users WHERE email=? AND active=TRUE",Long.class,auth.getName()); if(id==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED); return id; }
    private long[] assignment(long id,long user) { var rows=db.query("SELECT section_id,subject_id FROM faculty_assignments WHERE id=? AND faculty_user_id=? AND active=TRUE",(rs,n)->new long[]{rs.getLong(1),rs.getLong(2)},id,user); if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Class is not assigned to this faculty account."); return rows.getFirst(); }
    private Map<String,Object> sessionForFaculty(long session,long user) { var rows=db.query("SELECT cs.id,cs.section_id sectionId,cs.subject_id subjectId,cs.session_date sessionDate,cs.session_label sessionLabel,cs.state,cs.version FROM class_sessions cs JOIN faculty_assignments a ON a.section_id=cs.section_id AND a.subject_id=cs.subject_id AND a.faculty_user_id=? AND a.active=TRUE WHERE cs.id=?",(rs,n)->{Map<String,Object> m=new HashMap<>();m.put("id",rs.getLong("id"));m.put("sectionId",rs.getLong("sectionId"));m.put("subjectId",rs.getLong("subjectId"));m.put("sessionDate",rs.getDate("sessionDate"));m.put("sessionLabel",rs.getString("sessionLabel"));m.put("state",rs.getString("state"));m.put("version",rs.getLong("version"));return m;},user,session); if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Class session is not assigned to this faculty account."); return rows.getFirst(); }
}
