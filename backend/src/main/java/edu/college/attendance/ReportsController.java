package edu.college.attendance;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
@PreAuthorize("hasAnyRole('ADMIN','FACULTY')")
public class ReportsController {
    private final JdbcTemplate db;
    public ReportsController(JdbcTemplate db) { this.db=db; }
    @GetMapping("/attendance") List<Map<String,Object>> attendance(Authentication auth,@RequestParam(required=false) String from,@RequestParam(required=false) String to) {
        StringBuilder sql=new StringBuilder("SELECT cs.session_date sessionDate,c.name courseName,s.code sectionCode,sub.code subjectCode,sub.name subjectName,u.display_name studentName,u.email,ar.status FROM attendance_records ar JOIN class_sessions cs ON cs.id=ar.class_session_id JOIN sections s ON s.id=cs.section_id JOIN courses c ON c.id=s.course_id JOIN subjects sub ON sub.id=cs.subject_id JOIN users u ON u.id=ar.student_user_id WHERE cs.state='COMPLETED'");
        java.util.ArrayList<Object> args=new java.util.ArrayList<>();
        if(from!=null&&!from.isBlank()){sql.append(" AND cs.session_date>=?");args.add(java.sql.Date.valueOf(from));} if(to!=null&&!to.isBlank()){sql.append(" AND cs.session_date<=?");args.add(java.sql.Date.valueOf(to));}
        if(auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_FACULTY"))){sql.append(" AND EXISTS(SELECT 1 FROM faculty_assignments fa WHERE fa.faculty_user_id=(SELECT id FROM users WHERE email=?) AND fa.section_id=cs.section_id AND fa.subject_id=cs.subject_id AND fa.active=TRUE)");args.add(auth.getName());}
        sql.append(" ORDER BY cs.session_date DESC,c.name,s.code,u.display_name LIMIT 5000");return db.queryForList(sql.toString(),args.toArray());
    }
    @GetMapping(value="/attendance.csv",produces="text/csv") ResponseEntity<byte[]> csv(Authentication auth,@RequestParam(required=false) String from,@RequestParam(required=false) String to) {
        List<Map<String,Object>> rows=attendance(auth,from,to); StringBuilder out=new StringBuilder("Date,Course,Section,Subject,Student,Email,Status\r\n");
        for(Map<String,Object> r:rows) out.append(csv(r.get("sessionDate"))).append(',').append(csv(r.get("courseName"))).append(',').append(csv(r.get("sectionCode"))).append(',').append(csv(r.get("subjectName"))).append(',').append(csv(r.get("studentName"))).append(',').append(csv(r.get("email"))).append(',').append(csv(r.get("status"))).append("\r\n");
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=attendance-report.csv").contentType(new MediaType("text","csv",StandardCharsets.UTF_8)).body(out.toString().getBytes(StandardCharsets.UTF_8));
    }
    private String csv(Object value) { return "\""+String.valueOf(value==null?"":value).replace("\"","\"\"")+"\""; }
}
