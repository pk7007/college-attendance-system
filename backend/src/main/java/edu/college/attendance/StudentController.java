package edu.college.attendance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/student/attendance")
@PreAuthorize("hasRole('STUDENT')")
public class StudentController {
    private final JdbcTemplate db;
    public StudentController(JdbcTemplate db) { this.db=db; }
    @GetMapping("/summary") Map<String,Object> summary(Authentication auth) {
        long uid=userId(auth);
        Map<String,Object> counts=db.queryForMap("SELECT COUNT(*) total,COALESCE(SUM(ar.status='PRESENT'),0) present,COALESCE(SUM(ar.status='ABSENT'),0) absent,COALESCE(SUM(ar.status='LATE'),0) late,COALESCE(SUM(ar.status IN ('PRESENT','LATE')),0) attended FROM attendance_records ar JOIN class_sessions cs ON cs.id=ar.class_session_id WHERE ar.student_user_id=? AND cs.state='COMPLETED'",uid);
        long total=((Number)counts.get("total")).longValue(); long attended=((Number)counts.get("attended")).longValue();
        counts.put("percentage",total==0?null:Math.round(attended*10000.0/total)/100.0); counts.put("formula","(PRESENT + LATE) / completed sessions × 100; cancelled and incomplete sessions are excluded.");
        List<Map<String,Object>> subjects=db.queryForList("SELECT sub.id subjectId,sub.code subjectCode,sub.name subjectName,COUNT(*) total,SUM(ar.status IN ('PRESENT','LATE')) attended,ROUND(100.0*SUM(ar.status IN ('PRESENT','LATE'))/COUNT(*),2) percentage FROM attendance_records ar JOIN class_sessions cs ON cs.id=ar.class_session_id JOIN subjects sub ON sub.id=cs.subject_id WHERE ar.student_user_id=? AND cs.state='COMPLETED' GROUP BY sub.id,sub.code,sub.name ORDER BY sub.name",uid);
        counts.put("subjects",subjects); return counts;
    }
    @GetMapping("/history") Map<String,Object> history(Authentication auth,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size,@RequestParam(required=false) Long subjectId,@RequestParam(required=false) String from,@RequestParam(required=false) String to) {
        if(page<0||size<1||size>100) throw new IllegalArgumentException("Page must be nonnegative and size must be between 1 and 100.");
        long uid=userId(auth); StringBuilder where=new StringBuilder(" WHERE ar.student_user_id=? AND cs.state='COMPLETED'"); java.util.ArrayList<Object> args=new java.util.ArrayList<>();args.add(uid);
        if(subjectId!=null){where.append(" AND cs.subject_id=?");args.add(subjectId);} if(from!=null&&!from.isBlank()){where.append(" AND cs.session_date>=?");args.add(java.sql.Date.valueOf(from));} if(to!=null&&!to.isBlank()){where.append(" AND cs.session_date<=?");args.add(java.sql.Date.valueOf(to));}
        Long total=db.queryForObject("SELECT COUNT(*) FROM attendance_records ar JOIN class_sessions cs ON cs.id=ar.class_session_id"+where,args.toArray(),Long.class);
        String sql="SELECT ar.id,cs.session_date sessionDate,cs.session_label sessionLabel,sub.code subjectCode,sub.name subjectName,ar.status FROM attendance_records ar JOIN class_sessions cs ON cs.id=ar.class_session_id JOIN subjects sub ON sub.id=cs.subject_id"+where+" ORDER BY cs.session_date DESC,cs.id DESC LIMIT ? OFFSET ?";
        args.add(size);args.add(page*size); return Map.of("content",db.queryForList(sql,args.toArray()),"page",page,"size",size,"totalElements",total==null?0:total);
    }
    private long userId(Authentication auth) { Long id=db.queryForObject("SELECT id FROM users WHERE email=? AND active=TRUE",Long.class,auth.getName()); if(id==null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED); return id; }
}
