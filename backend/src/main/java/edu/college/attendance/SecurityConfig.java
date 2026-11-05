package edu.college.attendance;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean SecurityFilterChain security(HttpSecurity http, org.springframework.jdbc.core.JdbcTemplate db) throws Exception {
        var csrf = new CsrfTokenRequestAttributeHandler();
        http.csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()).csrfTokenRequestHandler(csrf))
            .authorizeHttpRequests(a -> a.requestMatchers("/", "/error", "/index.html", "/assets/**", "/api/health", "/api/auth/csrf", "/api/auth/login").permitAll().anyRequest().authenticated())
            .formLogin(f -> f.disable()).httpBasic(b -> b.disable())
            .exceptionHandling(e -> e.authenticationEntryPoint((request,response,exception) -> response.sendError(HttpStatus.UNAUTHORIZED.value())))
            .logout(l -> l.disable());
        http.addFilterBefore(new org.springframework.web.filter.OncePerRequestFilter() {
            @Override protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res, jakarta.servlet.FilterChain chain) throws java.io.IOException, jakarta.servlet.ServletException {
                var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                if(auth!=null && auth.isAuthenticated() && !(auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)) {
                    Integer count=db.queryForObject("SELECT COUNT(*) FROM users WHERE email=? AND active=TRUE",Integer.class,auth.getName());
                    if(count==null||count==0){new org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler().logout(req,res,auth);res.sendError(401);return;}
                }
                chain.doFilter(req,res);
            }
        },org.springframework.security.web.access.intercept.AuthorizationFilter.class);
        return http.build();
    }
}
