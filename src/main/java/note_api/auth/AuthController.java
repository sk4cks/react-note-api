package note_api.auth;

import note_api.auth.dto.LoginRequest;
import note_api.auth.dto.TokenExchangeRequest;
import note_api.auth.dto.TokenResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
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
    private final RefreshTokenCookieService refreshTokenCookieService;

    public AuthController(AuthService authService, RefreshTokenCookieService refreshTokenCookieService) {
        this.authService = authService;
        this.refreshTokenCookieService = refreshTokenCookieService;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(
            @RequestBody LoginRequest request, HttpServletResponse response) {
        TokenResponse tokens = authService.login(request);
        refreshTokenCookieService.writeRefreshToken(response, tokens.refreshToken());
        return ResponseEntity.ok(withoutRefreshToken(tokens));
    }

    @GetMapping("/social/prepare/{provider}")
    public void socialPrepare(
            @PathVariable String provider,
            @RequestParam String state,
            @RequestParam("code_challenge") String codeChallenge,
            @RequestParam("redirect_uri") String redirectUri,
            HttpServletResponse response) throws IOException {
        authService.redirectToSocialPrepare(provider, state, codeChallenge, redirectUri, response);
    }

    @PostMapping("/token")
    public ResponseEntity<TokenResponse> token(
            @RequestBody TokenExchangeRequest request, HttpServletResponse response) {
        TokenResponse tokens = authService.exchangeToken(request);
        refreshTokenCookieService.writeRefreshToken(response, tokens.refreshToken());
        return ResponseEntity.ok(withoutRefreshToken(tokens));
    }

    /** refresh_token은 HttpOnly cookie — body 없음 */
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = refreshTokenCookieService.readRefreshToken(request);
        if (!StringUtils.hasText(refreshToken)) {
            return ResponseEntity.status(401).build();
        }
        TokenResponse tokens = authService.refreshToken(refreshToken);
        if (StringUtils.hasText(tokens.refreshToken())) {
            refreshTokenCookieService.writeRefreshToken(response, tokens.refreshToken());
        }
        return ResponseEntity.ok(withoutRefreshToken(tokens));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse response) {
        refreshTokenCookieService.clearRefreshToken(response);
        return ResponseEntity.noContent().build();
    }

    private static TokenResponse withoutRefreshToken(TokenResponse tokens) {
        return new TokenResponse(
                tokens.accessToken(),
                tokens.tokenType(),
                tokens.expiresIn(),
                null,
                tokens.scope());
    }
}
