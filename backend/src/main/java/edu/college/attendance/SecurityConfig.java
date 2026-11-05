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
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        var csrf = new CsrfTokenRequestAttributeHandler();
        http.csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()).csrfTokenRequestHandler(csrf))
            .authorizeHttpRequests(a -> a.requestMatchers("/", "/index.html", "/assets/**", "/api/health", "/api/auth/csrf", "/api/auth/login").permitAll().anyRequest().authenticated())
            .formLogin(f -> f.disable()).httpBasic(b -> b.disable())
            .exceptionHandling(e -> e.authenticationEntryPoint((request,response,exception) -> response.sendError(HttpStatus.UNAUTHORIZED.value())))
            .logout(l -> l.disable());
        return http.build();
    }
}
