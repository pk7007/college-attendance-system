package edu.college.attendance;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    /** Paths a user with a temporary password may still call. Everything else waits until they choose their own password. */
    private static final Set<String> TEMPORARY_PASSWORD_PATHS = Set.of("/api/auth/me", "/api/auth/password", "/api/auth/logout", "/api/auth/csrf");

    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }

    @Bean
    SecurityFilterChain security(HttpSecurity http, JdbcTemplate db) throws Exception {
        http.csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/", "/error", "/index.html", "/favicon.ico", "/api/health", "/api/auth/csrf", "/api/auth/login").permitAll()
                .anyRequest().authenticated())
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .logout(l -> l.disable())
            .headers(h -> h
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'; form-action 'self'"))
                .referrerPolicy(r -> r.policy(ReferrerPolicy.SAME_ORIGIN)))
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> json(res, 401, "Sign in to continue."))
                .accessDeniedHandler((req, res, ex) -> json(res, 403, "Your session expired or the page is out of date. Reload and try again.")));

        // Re-checks the account on every request so deactivation and temporary passwords take effect immediately for that user.
        http.addFilterBefore(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
                var auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
                    List<Boolean> mustChange = db.query("SELECT must_change_password FROM users WHERE email=? AND active=TRUE", (rs, n) -> rs.getBoolean(1), auth.getName());
                    if (mustChange.isEmpty()) {
                        new SecurityContextLogoutHandler().logout(req, res, auth);
                        json(res, 401, "Your account is disabled or no longer exists. Contact the administrator.");
                        return;
                    }
                    String path = req.getRequestURI().substring(req.getContextPath().length());
                    if (mustChange.getFirst() && path.startsWith("/api/") && !TEMPORARY_PASSWORD_PATHS.contains(path)) {
                        json(res, 403, "Change your temporary password before continuing.");
                        return;
                    }
                }
                chain.doFilter(req, res);
            }
        }, AuthorizationFilter.class);
        return http.build();
    }

    /** Writes the same {"error": "..."} body the controllers use. Messages are fixed strings, so no escaping beyond quotes is needed. */
    private static void json(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
    }
}
