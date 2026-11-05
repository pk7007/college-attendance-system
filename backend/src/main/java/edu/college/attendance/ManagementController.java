package edu.college.attendance;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/manage")
@PreAuthorize("hasRole('ADMIN')")
public class ManagementController {
    private final JdbcTemplate db;
    public ManagementController(JdbcTemplate db){this.db=db;}
    private static final Map<String,String> FIELDS=Map.of("users","display_name","courses","name","subjects","name","sections","code","departments","name","student_enrollments","status","faculty_assignments","active");
    private String field(String entity){String f=FIELDS.get(entity);if(f==null)throw new IllegalArgumentException("Unknown resource");return f;}
    @GetMapping("/{entity}") List<Map<String,Object>> list(@PathVariable String entity){
        String f=field(entity);
        if(entity.equals("student_enrollments"))return db.queryForList("SELECT e.id,CONCAT(u.display_name,' / ',s.code) label,e.status value,e.status='ACTIVE' active FROM student_enrollments e JOIN users u ON u.id=e.student_user_id JOIN sections s ON s.id=e.section_id");
        if(entity.equals("faculty_assignments"))return db.queryForList("SELECT a.id,CONCAT(u.display_name,' / ',s.code,' / ',sub.name) label,a.active value,a.active FROM faculty_assignments a JOIN users u ON u.id=a.faculty_user_id JOIN sections s ON s.id=a.section_id JOIN subjects sub ON sub.id=a.subject_id");
        return db.queryForList("SELECT id,"+f+" label,"+f+" value,active FROM "+entity+" ORDER BY id");
    }
    public record Update(String value,Boolean active){}
    @PutMapping("/{entity}/{id}") @Transactional Map<String,Object> update(@PathVariable String entity,@PathVariable long id,@RequestBody Update input,Authentication auth){
        String f=field(entity); if(input.active()==null)throw new IllegalArgumentException("Active status is required");
        if(entity.equals("users")&&!input.active()){
            Integer target=db.queryForObject("SELECT COUNT(*) FROM users WHERE id=? AND role='ADMIN' AND active=TRUE",Integer.class,id);
            Integer count=db.queryForObject("SELECT COUNT(*) FROM users WHERE role='ADMIN' AND active=TRUE",Integer.class);
            if(target!=null&&target>0&&count!=null&&count<=1)throw new IllegalArgumentException("The last active administrator cannot be disabled");
        }
        int changed;
        if(entity.equals("student_enrollments"))changed=db.update("UPDATE student_enrollments SET status=? WHERE id=?",input.active()?"ACTIVE":"INACTIVE",id);
        else if(entity.equals("faculty_assignments"))changed=db.update("UPDATE faculty_assignments SET active=? WHERE id=?",input.active(),id);
        else{
            if(input.value()==null||input.value().isBlank()||input.value().length()>(entity.equals("sections")?30:120))throw new IllegalArgumentException("Enter a valid name/code");
            changed=db.update("UPDATE "+entity+" SET "+f+"=?,active=? WHERE id=?",input.value().trim(),input.active(),id);
        }
        if(changed!=1)throw new IllegalArgumentException("Record not found");
        db.update("INSERT INTO audit_events(actor_user_id,action,entity_type,entity_id) VALUES((SELECT id FROM users WHERE email=?),'RECORD_UPDATED',?,?)",auth.getName(),entity,id);
        return Map.of("id",id,"updated",true);
    }
}
