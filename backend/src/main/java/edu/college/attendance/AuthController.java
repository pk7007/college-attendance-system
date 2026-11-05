package edu.college.attendance;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;
    private final SecurityContextRepository contexts;
    public AuthController(JdbcTemplate db, PasswordEncoder passwords, SecurityContextRepository contexts) { this.db=db; this.passwords=passwords; this.contexts=contexts; }

    public record Login(@Email @NotBlank String email, @NotBlank String password) {}
    public record ChangePassword(@NotBlank String currentPassword, @NotBlank @Size(min=12,max=100) String newPassword) {}

    @GetMapping("/csrf") Map<String,String> csrf(CsrfToken token) { return Map.of("token", token.getToken()); }

    @PostMapping("/login") Map<String,Object> login(@Valid @RequestBody Login input, HttpServletRequest request, HttpServletResponse response) {
        var users = db.query("SELECT id,email,password_hash,display_name,role,active,must_change_password FROM users WHERE email=?", (rs,n) -> new User(rs.getLong("id"),rs.getString("email"),rs.getString("password_hash"),rs.getString("display_name"),rs.getString("role"),rs.getBoolean("active"),rs.getBoolean("must_change_password")), input.email().trim().toLowerCase());
        if (users.isEmpty() || !users.getFirst().active() || !passwords.matches(input.password(), users.getFirst().passwordHash())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Email or password is incorrect.");
        var user=users.getFirst();
        var auth=UsernamePasswordAuthenticationToken.authenticated(user.email(),null,List.of(new SimpleGrantedAuthority("ROLE_"+user.role())));
        request.getSession(true); request.changeSessionId();
        var context=SecurityContextHolder.createEmptyContext(); context.setAuthentication(auth); SecurityContextHolder.setContext(context); contexts.saveContext(context,request,response);
        request.getSession(false).setAttribute("userId",user.id());
        return profile(user);
    }

    @GetMapping("/me") Map<String,Object> me(org.springframework.security.core.Authentication auth) {
        return db.queryForMap("SELECT id,email,display_name displayName,role,must_change_password mustChangePassword FROM users WHERE email=? AND active=TRUE",auth.getName());
    }

    @PostMapping("/logout") Map<String,String> logout(HttpServletRequest request,HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request,response,SecurityContextHolder.getContext().getAuthentication());
        return Map.of("status","signed out");
    }

    @PostMapping("/password") Map<String,String> changePassword(@Valid @RequestBody ChangePassword input,org.springframework.security.core.Authentication auth) {
        var rows=db.query("SELECT id,password_hash FROM users WHERE email=?",(rs,n)->new Object[]{rs.getLong(1),rs.getString(2)},auth.getName());
        if(rows.isEmpty() || !passwords.matches(input.currentPassword(),(String)rows.getFirst()[1])) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Current password is incorrect.");
        db.update("UPDATE users SET password_hash=?,must_change_password=FALSE WHERE id=?",passwords.encode(input.newPassword()),rows.getFirst()[0]);
        return Map.of("status","password changed");
    }

    private Map<String,Object> profile(User u) { return Map.of("id",u.id(),"email",u.email(),"displayName",u.displayName(),"role",u.role(),"mustChangePassword",u.mustChangePassword()); }
    private record User(long id,String email,String passwordHash,String displayName,String role,boolean active,boolean mustChangePassword) {}
}
