package note_api.auth;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /** SNS 시작 — 프론트는 API만 호출, BFF가 Auth Server prepare로 redirect */
    @GetMapping("/social/prepare/{provider}")
    public void socialPrepare(
            @PathVariable String provider,
            @RequestParam String state,
            @RequestParam("code_challenge") String codeChallenge,
            @RequestParam("redirect_uri") String redirectUri,
            HttpServletResponse response) throws IOException {
        authService.redirectToSocialPrepare(provider, state, codeChallenge, redirectUri, response);
    }

    /** SNS 등 authorization_code + PKCE 완료 후 토큰 교환 */
    @PostMapping("/token")
    public ResponseEntity<TokenResponse> token(@RequestBody TokenExchangeRequest request) {
        return ResponseEntity.ok(authService.exchangeToken(request));
    }
}
