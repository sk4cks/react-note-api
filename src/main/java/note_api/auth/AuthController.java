package note_api.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    /** SNS 등 authorization_code + PKCE 완료 후 토큰 교환 */
    @PostMapping("/token")
    public ResponseEntity<TokenResponse> token(@RequestBody TokenExchangeRequest request) {
        return ResponseEntity.ok(authService.exchangeToken(request));
    }
}
