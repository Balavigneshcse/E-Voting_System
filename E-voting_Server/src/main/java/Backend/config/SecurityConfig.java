package Backend.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * Web security for the two audiences that reach this server.
 *
 * <h2>Voting terminals — {@code /api/**}</h2>
 * Left as {@code permitAll} here because Spring Security is the wrong layer for it:
 * terminals are not browser sessions. They are authenticated by
 * {@link Backend.security.MachineAuthenticationFilter}, which runs ahead of this chain and
 * enforces TLS, a revocable JWT, a per-terminal HMAC signature, and replay protection.
 * A request that fails any of those never reaches a controller.
 *
 * <h2>Administrators — everything else</h2>
 * Session login, role-gated, CSRF-protected.
 *
 * <p>Two routes are gone rather than merely denied: the legacy browser voting flow
 * ({@code /nfc/**}, {@code index.html}, {@code Biometric.html}, {@code vote.html}) and the
 * dead {@code /vote}, {@code /login/**} and {@code /candidates/**} endpoints. Keeping a
 * second, unauthenticated path to the ballot box while blocking it in configuration was
 * one revert away from being live again.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] ADMIN_ROLES = {
            "SUPER_ADMIN", "PM_ADMIN", "CM_ADMIN", "DATA_ADMIN"
    };

    @Value("${evoting.admin.username}")
    private String adminUsername;

    @Value("${evoting.admin.password}")
    private String adminPassword;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                // The dashboard's JS reads the token straight out of the XSRF-TOKEN cookie
                // and sends that same value back as the X-XSRF-TOKEN header — no server-
                // rendered form or meta tag involved. Spring Security 6's default handler,
                // XorCsrfTokenRequestAttributeHandler, BREACH-masks the token wherever it's
                // rendered and expects the client to send that masked form back, then
                // unmasks it before comparing. A client sending the raw cookie value instead
                // gets "unmasked" into garbage and every POST fails CSRF validation with a
                // 403 that looks exactly like an expired session. The plain handler skips
                // the masking round-trip entirely, which is what a raw-cookie-reading client
                // needs — this is Spring's own documented approach for that case.
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                // Terminals carry no session cookie, so CSRF does not apply to them. Their
                // integrity guarantee is the per-request HMAC signature instead.
                .ignoringRequestMatchers("/api/**", "/perform-login"))
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; script-src 'self' 'unsafe-inline'; "
                        + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
                        + "connect-src 'self'; object-src 'none'; base-uri 'self'; "
                        + "frame-ancestors 'none'"))
                .frameOptions(frame -> frame.deny())
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31536000))
                .referrerPolicy(referrer -> referrer.policy(
                    org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                        .ReferrerPolicy.NO_REFERRER)))
            .authorizeHttpRequests(auth -> auth
                // Authenticated ahead of this chain by MachineAuthenticationFilter.
                .requestMatchers("/api/**").permitAll()
                .requestMatchers("/", "/index.html", "/Login.html", "/login.html",
                                 "/perform-login", "/auth/csrf", "/error").permitAll()
                .requestMatchers("/Admin.html", "/admin.html", "/admin/**",
                                 "/auth/me", "/settings/language", "/translations/**")
                    .hasAnyRole(ADMIN_ROLES)
                .anyRequest().denyAll()
            )
            .formLogin(form -> form
                .loginPage("/Login.html")
                .loginProcessingUrl("/perform-login")
                .defaultSuccessUrl("/Admin.html", true)
                .failureUrl("/Login.html?error=true")
                .permitAll()
            )
            .logout(logout -> logout
                .logoutRequestMatcher(new AntPathRequestMatcher("/perform-logout", "GET"))
                .logoutSuccessUrl("/Login.html?logout=true")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
                .permitAll()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, e) -> {
                    String accept = request.getHeader("Accept");
                    if (accept != null && accept.contains("application/json")) {
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.setContentType("application/json");
                        response.getWriter().write("{\"error\":\"Unauthorized\"}");
                    } else {
                        response.sendRedirect("/Login.html");
                    }
                })
            );
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        UserDetails superAdmin = User.builder()
            .username(adminUsername)
            .password(encoder.encode(adminPassword))
            .roles(ADMIN_ROLES)
            .build();
        return new InMemoryUserDetailsManager(superAdmin);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
