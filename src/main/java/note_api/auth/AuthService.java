package note_api.auth;

import note_api.auth.dto.LoginRequest;
import note_api.auth.dto.OnboardingStatusResponse;
import note_api.auth.dto.RegisterRequest;
import note_api.auth.dto.SocialCompleteRequest;
import note_api.auth.dto.TokenExchangeRequest;
import note_api.auth.dto.TokenResponse;
import note_api.auth.dto.UserResponse;
import note_api.common.exception.AuthServerException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

/**
 * BFF 인증 유스케이스 — 프론트는 이 서비스(경유 Controller)만 호출하고, Auth Server는 {@link AuthServerClient}로만 접근한다.
 * <p>
 * 토큰 정책:
 * <ul>
 *   <li>access_token — JSON body (프론트 sessionStorage)</li>
 *   <li>refresh_token — Controller에서 HttpOnly cookie로 저장 ({@link RefreshTokenCookieService})</li>
 * </ul>
 * register/social complete/login 4xx 는 {@link AuthServerException}으로 Auth status+body 를 그대로 전달.
 */
@Service
public class AuthService {

    private final AuthServerClient authServerClient;

    public AuthService(AuthServerClient authServerClient) {
        this.authServerClient = authServerClient;
    }

    /**
     * 로컬 계정 로그인.
     * Auth Server {@code POST /auth/login} → access/refresh 토큰.
     * 컨트롤러가 refresh를 cookie에 넣고 body에서는 제거한다.
     */
    public TokenResponse login(LoginRequest request) {
        try {
            ResponseEntity<TokenResponse> response = authServerClient.login(request);
            TokenResponse body = response.getBody();
            if (body == null) {
                throw new IllegalStateException("Login failed: empty response from auth server");
            }
            return body;
        } catch (HttpStatusCodeException ex) {
            throw new AuthServerException(ex.getStatusCode(), ex.getResponseBodyAsString());
        }
    }

    /**
     * 로컬 회원가입.
     * Auth Server {@code POST /auth/register} → SYS_USER INSERT (비밀번호 해시, mailAddress 부여).
     * 토큰은 발급하지 않음 — 가입 후 프론트가 /login 으로 이동.
     * 중복 userId 등은 Auth의 409를 {@link AuthServerException}으로 전파.
     */
    public UserResponse register(RegisterRequest request) {
        try {
            ResponseEntity<UserResponse> response = authServerClient.register(request);
            UserResponse body = response.getBody();
            if (body == null) {
                throw new IllegalStateException("Register failed: empty response from auth server");
            }
            return body;
        } catch (HttpStatusCodeException ex) {
            throw new AuthServerException(ex.getStatusCode(), ex.getResponseBodyAsString());
        }
    }

    /**
     * SNS 로그인 직후 온보딩(userId 선택) 필요 여부.
     * JWT의 {@code sns_provider}/{@code sns_external_id} 로 Auth Server에 등록 여부를 조회한다.
     * <ul>
     *   <li>sns_* 클레임 없음 → 로컬/이미 정리된 토큰 → needsUserId=false</li>
     *   <li>등록 안 됨 → needsUserId=true (프론트 /onboarding)</li>
     *   <li>등록됨 → needsUserId=false + DB userId</li>
     * </ul>
     */
    public OnboardingStatusResponse getOnboardingStatus(Jwt jwt) {
        String snsProvider = jwt.getClaimAsString("sns_provider");
        String snsExternalId = jwt.getClaimAsString("sns_external_id");
        // SNS 브릿지로 발급된 토큰이 아니면 온보딩 불필요
        if (!StringUtils.hasText(snsProvider) || !StringUtils.hasText(snsExternalId)) {
            return new OnboardingStatusResponse(false, jwt.getSubject());
        }
        AuthServerClient.SocialUserStatus status =
                authServerClient.getSocialUserStatus(snsProvider, snsExternalId);
        if (!status.registered()) {
            return new OnboardingStatusResponse(true, null);
        }
        return new OnboardingStatusResponse(false, status.userId());
    }

    /**
     * SNS 최초 로그인 — 사용자가 고른 userId로 SYS_USER 생성 후 정식 토큰 재발급.
     * JWT sns_* 클레임 + 요청 userId 를 Auth Server {@code POST /auth/social/register} 로 넘긴다.
     * 응답 토큰의 sub 는 선택 userId, email 은 userId@도메인.
     */
    public TokenResponse completeSocialOnboarding(Jwt jwt, SocialCompleteRequest request) {
        String snsProvider = jwt.getClaimAsString("sns_provider");
        String snsExternalId = jwt.getClaimAsString("sns_external_id");
        String snsExternalEmail = jwt.getClaimAsString("sns_external_email");
        if (!StringUtils.hasText(snsProvider) || !StringUtils.hasText(snsExternalId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SNS claims missing in token");
        }
        try {
            ResponseEntity<TokenResponse> response = authServerClient.completeSocialRegistration(
                    snsProvider, snsExternalId, snsExternalEmail, request.userId());
            TokenResponse body = response.getBody();
            if (body == null || !StringUtils.hasText(body.accessToken())) {
                throw new IllegalStateException("Social register failed: empty response from auth server");
            }
            return body;
        } catch (HttpStatusCodeException ex) {
            throw new AuthServerException(ex.getStatusCode(), ex.getResponseBodyAsString());
        }
    }

    /**
     * SNS 로그인 시작 — 브라우저를 Auth Server public URL의 prepare 로 302 redirect.
     * 프론트는 BFF {@code GET /api/auth/social/prepare/{provider}} 만 호출하고 Auth :9000 은 직접 치지 않는다.
     */
    public void redirectToSocialPrepare(
            String provider,
            String state,
            String codeChallenge,
            String redirectUri,
            HttpServletResponse response) throws IOException {
        String target = authServerClient.buildSocialPrepareRedirectUrl(
                provider, state, codeChallenge, redirectUri);
        response.sendRedirect(target);
    }

    /**
     * SNS OAuth callback — authorization_code + PKCE 를 access/refresh 로 교환.
     * Auth Server {@code POST /oauth2/token} (grant_type=authorization_code).
     */
    public TokenResponse exchangeToken(TokenExchangeRequest request) {
        try {
            ResponseEntity<TokenResponse> response = authServerClient.exchangeAuthorizationCode(request);
            TokenResponse body = response.getBody();
            if (body == null) {
                throw new IllegalStateException("Token exchange failed: empty response from auth server");
            }
            return body;
        } catch (HttpStatusCodeException ex) {
            throw new IllegalStateException("Token exchange failed: " + ex.getResponseBodyAsString(), ex);
        }
    }

    /**
     * access_token 갱신.
     * refresh_token 은 요청 body가 아니라 cookie에서 읽어 Controller가 넘긴다.
     * Auth Server {@code POST /oauth2/token} (grant_type=refresh_token).
     */
    public TokenResponse refreshToken(String refreshToken) {
        try {
            ResponseEntity<TokenResponse> response = authServerClient.refreshToken(refreshToken);
            TokenResponse body = response.getBody();
            if (body == null) {
                throw new IllegalStateException("Token refresh failed: empty response from auth server");
            }
            return body;
        } catch (HttpStatusCodeException ex) {
            throw new IllegalStateException("Token refresh failed: " + ex.getResponseBodyAsString(), ex);
        }
    }
}
