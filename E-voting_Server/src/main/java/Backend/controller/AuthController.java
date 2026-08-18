package Backend.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Session identity for the admin dashboard.
 *
 * <p>The dashboard is a static single page, so it cannot inspect its own Spring
 * Security session; it asks these two endpoints instead. Machine authentication is
 * unrelated and lives entirely in {@link Backend.security.MachineAuthenticationFilter}.
 *
 * <p>No {@code @CrossOrigin} here, deliberately — see the note on
 * {@link ElectionResultsController}. The dashboard is served from the same origin as
 * this API, so a wildcard CORS policy on a session-authenticated, role-gated endpoint
 * would only widen the attack surface for no benefit. A stray {@code @CrossOrigin("*")}
 * on this exact controller was found and removed rather than carried forward.
 */
@RestController
public class AuthController {

    /** Forces creation of the XSRF-TOKEN cookie the same-origin dashboard reads before a POST. */
    @GetMapping("/auth/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken());
    }

    /** Who is signed in, and which slice of the election their role administers. */
    @GetMapping("/auth/me")
    public Map<String, Object> getCurrentUser(Authentication authentication) {
        Map<String, Object> response = new HashMap<>();
        if (authentication == null || !authentication.isAuthenticated()) {
            response.put("authenticated", false);
            return response;
        }
        response.put("authenticated", true);
        response.put("username", authentication.getName());

        List<String> roles = new ArrayList<>();
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            roles.add(authority.getAuthority().replace("ROLE_", ""));
        }
        response.put("roles", roles);

        boolean isSuperAdmin = roles.contains("SUPER_ADMIN");
        boolean isDataAdmin  = roles.contains("DATA_ADMIN");
        boolean isPmAdmin    = roles.contains("PM_ADMIN");
        boolean isCmAdmin    = roles.contains("CM_ADMIN");

        String electionType;
        if (isSuperAdmin) {
            electionType = "ALL";
        } else if (isPmAdmin) {
            electionType = "PM";
        } else if (isCmAdmin) {
            electionType = "CM";
        } else if (isDataAdmin) {
            electionType = "DATA";
        } else {
            electionType = null;
        }

        response.put("electionType", electionType);
        response.put("isSuperAdmin", isSuperAdmin);
        response.put("isDataAdmin", isDataAdmin);
        return response;
    }
}
