package Backend.config;

import jakarta.servlet.http.HttpServletResponse;
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

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                // ── Machine APIs: /api/** are open — secured by X-Machine-Token header (MachineApiController)
                .requestMatchers("/api/**").permitAll()

                // ── Original admin page still uses Spring Security form login
                .requestMatchers("/Admin.html", "/admin.html").hasAnyRole(
                    "SUPER_ADMIN","PM_ADMIN","CM_ADMIN","MUNICIPALITY_ADMIN","DATA_ADMIN")

                // ── Everything else is open (NFC voting pages, static files)
                .anyRequest().permitAll()
            )
            .formLogin(form -> form
                .loginPage("/Login.html")
                .loginProcessingUrl("/perform-login")
                .defaultSuccessUrl("/Admin.html", true)
                .failureUrl("/Login.html?error=true")
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/perform-logout")
                .logoutSuccessUrl("/Login.html?logout=true")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
                .permitAll()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) -> {
                    String accept = req.getHeader("Accept");
                    if (accept != null && accept.contains("application/json")) {
                        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        res.setContentType("application/json");
                        res.getWriter().write("{\"error\":\"Unauthorized\"}");
                    } else {
                        res.sendRedirect("/Login.html");
                    }
                })
            );
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        UserDetails superAdmin = User.builder()
            .username("Logic Makers")
            .password(encoder.encode("logic"))
            .roles("SUPER_ADMIN","PM_ADMIN","CM_ADMIN","MUNICIPALITY_ADMIN","DATA_ADMIN")
            .build();
        UserDetails pmAdmin = User.builder()
            .username("Bala")
            .password(encoder.encode("922524104016"))
            .roles("PM_ADMIN")
            .build();
        UserDetails cmAdmin = User.builder()
            .username("Arun")
            .password(encoder.encode("922524104011"))
            .roles("CM_ADMIN")
            .build();
        UserDetails munAdmin = User.builder()
            .username("Deepak")
            .password(encoder.encode("922524104026"))
            .roles("MUNICIPALITY_ADMIN")
            .build();
        UserDetails dataAdmin = User.builder()
            .username("Dinesh")
            .password(encoder.encode("922524104040"))
            .roles("DATA_ADMIN")
            .build();
        return new InMemoryUserDetailsManager(superAdmin, pmAdmin, cmAdmin, munAdmin, dataAdmin);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
