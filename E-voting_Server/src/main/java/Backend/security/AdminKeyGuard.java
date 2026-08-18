package Backend.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Second factor for the handful of privileged operations a terminal can perform.
 *
 * <p>Machine authentication proves <em>which terminal</em> is calling. Opening or
 * closing polling, and reading results, additionally require proof that an
 * <em>election officer</em> is present, which is what this key represents. A terminal
 * left unattended therefore cannot open polling by itself.
 */
@Component
public class AdminKeyGuard {

    public static final String HEADER = "X-Admin-Key";

    private final String adminKey;

    public AdminKeyGuard(@Value("${evoting.admin-key:}") String adminKey) {
        this.adminKey = adminKey;
    }

    public boolean isAuthorised(HttpServletRequest request) {
        if (adminKey == null || adminKey.isBlank()) {
            // An unset key must deny everything rather than allow everything.
            return false;
        }
        return CryptoSupport.constantTimeEquals(adminKey, request.getHeader(HEADER));
    }
}
