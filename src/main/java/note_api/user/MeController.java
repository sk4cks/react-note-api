package note_api.user;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/** 로그인 사용자 정보. JWT claim만 돌려준다. */
@RestController
@RequestMapping("/api")
public class MeController {

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> result = new HashMap<>();
        result.put("userId", jwt.getSubject());
        result.put("preferredUsername", jwt.getClaimAsString("preferred_username"));
        result.put("scope", jwt.getClaimAsString("scope"));

        return result;
    }
}
