package note_api.auth;

import jakarta.validation.Valid;
import note_api.auth.dto.LoginRequest;
import note_api.auth.dto.OnboardingStatusResponse;
import note_api.auth.dto.RegisterRequest;
import note_api.auth.dto.SocialCompleteRequest;
import note_api.auth.dto.TokenExchangeRequest;
import note_api.auth.dto.TokenResponse;
import note_api.auth.dto.UserIdAvailabilityResponse;
import note_api.auth.dto.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** 프론트 인증 API. refresh_token은 HttpOnly cookie, access_token만 JSON. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookieService refreshTokenCookieService;

    public AuthController(AuthService authService, RefreshTokenCookieService refreshTokenCookieService) {
        this.authService = authService;
        this.refreshTokenCookieService = refreshTokenCookieService;
    }

    /** 로컬 로그인. refresh는 cookie, body에는 access만. */
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@RequestBody LoginRequest request, HttpServletResponse response) {
        TokenResponse tokens = authService.login(request);

        return okWithRefreshCookie(tokens, response);
    }

    /** 로컬 회원가입. 토큰은 없고 이후 /login. */
    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        UserResponse user = authService.register(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(user);
    }

    /** 회원가입 전 아이디 중복 확인 */
    @GetMapping("/check-userid")
    public ResponseEntity<UserIdAvailabilityResponse> checkUserId(@RequestParam String userId) {
        return ResponseEntity.ok(authService.checkUserId(userId));
    }

    /** SNS 최초 로그인 — SYS_USER 등록 여부 (JWT sns_* 클레임 기준) */
    @GetMapping("/onboarding-status")
    public ResponseEntity<OnboardingStatusResponse> onboardingStatus(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(authService.getOnboardingStatus(jwt));
    }

    /** SNS 최초 로그인 — userId 선택 후 토큰 재발급 */
    @PostMapping("/social/complete")
    public ResponseEntity<TokenResponse> completeSocial(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SocialCompleteRequest request, HttpServletResponse response) {
        TokenResponse tokens = authService.completeSocialOnboarding(jwt, request);

        return okWithRefreshCookie(tokens, response);
    }

    /** SNS 로그인 시작. 브라우저를 Auth public URL로 302. */
    @GetMapping("/social/prepare/{provider}")
    public void socialPrepare(@PathVariable String provider, @RequestParam String state, @RequestParam("code_challenge") String codeChallenge, @RequestParam("redirect_uri") String redirectUri, HttpServletResponse response) throws IOException {
        authService.redirectToSocialPrepare(provider, state, codeChallenge, redirectUri, response);
    }

    /** SNS callback — authorization_code → 토큰. */
    @PostMapping("/token")
    public ResponseEntity<TokenResponse> token(@RequestBody TokenExchangeRequest request, HttpServletResponse response) {
        TokenResponse tokens = authService.exchangeToken(request);

        return okWithRefreshCookie(tokens, response);
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

    /** refresh cookie 삭제. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse response) {
        refreshTokenCookieService.clearRefreshToken(response);

        return ResponseEntity.noContent().build();
    }

    /** refresh를 cookie에 넣고 JSON에서는 뺀다. */
    private ResponseEntity<TokenResponse> okWithRefreshCookie(
            TokenResponse tokens, HttpServletResponse response) {
        refreshTokenCookieService.writeRefreshToken(response, tokens.refreshToken());

        return ResponseEntity.ok(withoutRefreshToken(tokens));
    }

    /** JSON에 refresh_token을 넣지 않는다. cookie로만 준다. */
    private static TokenResponse withoutRefreshToken(TokenResponse tokens) {
        return new TokenResponse(
                tokens.accessToken(),
                tokens.tokenType(),
                tokens.expiresIn(),
                null,
                tokens.scope());
    }
}
