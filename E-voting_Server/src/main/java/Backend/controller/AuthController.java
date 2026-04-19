package Backend.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@CrossOrigin("*")
public class AuthController {

    @GetMapping("/auth/me")
    public Map<String, Object> getCurrentUser(Authentication auth) {
        Map<String, Object> res = new HashMap<>();
        if (auth == null || !auth.isAuthenticated()) {
            res.put("authenticated", false);
            return res;
        }
        res.put("authenticated", true);
        res.put("username", auth.getName());

        List<String> roles = new ArrayList<>();
        for (GrantedAuthority a : auth.getAuthorities()) {
            roles.add(a.getAuthority().replace("ROLE_", ""));
        }
        res.put("roles", roles);

        boolean isSuperAdmin      = roles.contains("SUPER_ADMIN");
        boolean isDataAdmin       = roles.contains("DATA_ADMIN");
        boolean isPmAdmin         = roles.contains("PM_ADMIN");
        boolean isCmAdmin         = roles.contains("CM_ADMIN");
        boolean isMunAdmin        = roles.contains("MUNICIPALITY_ADMIN");

        String electionType = null;
        if (isSuperAdmin)  electionType = "ALL";
        else if (isPmAdmin)  electionType = "PM";
        else if (isCmAdmin)  electionType = "CM";
        else if (isMunAdmin) electionType = "MUNICIPALITY";
        else if (isDataAdmin) electionType = "DATA";

        res.put("electionType",  electionType);
        res.put("isSuperAdmin",  isSuperAdmin);
        res.put("isDataAdmin",   isDataAdmin);
        return res;
    }
}