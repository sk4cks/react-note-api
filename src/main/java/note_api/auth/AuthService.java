package note_api.auth;

import note_api.auth.dto.LoginRequest;
import note_api.auth.dto.OnboardingStatusResponse;
import note_api.auth.dto.RegisterRequest;
import note_api.auth.dto.SocialCompleteRequest;
import note_api.auth.dto.TokenExchangeRequest;
import note_api.auth.dto.TokenResponse;
import note_api.auth.dto.UserIdAvailabilityResponse;
import note_api.auth.dto.UserResponse;
import note_api.common.exception.AuthServerException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * BFF 인증 유스케이스. 프론트는 이 서비스(경유 Controller)만 호출, Auth Server는 {@link AuthServerClient}로만 접근.
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

    /** Auth Server 호출 클라이언트 주입. */
    public AuthService(AuthServerClient authServerClient) {
        this.authServerClient = authServerClient;
    }

    /**
     * 로컬 계정 로그인.
     * Auth Server {@code POST /auth/login} → access/refresh 토큰.
     * 컨트롤러가 refresh를 cookie에 넣고 body에서는 제거.
     */
    public TokenResponse login(LoginRequest request) {
        // Auth Server POST /auth/login. refresh는 컨트롤러가 cookie로 빼는 위임.
        return requireAuthBody(() -> authServerClient.login(request).getBody(), "Login");
    }

    /**
     * 아이디 중복 확인 — Auth Server {@code GET /auth/check-userid}.
     */
    public UserIdAvailabilityResponse checkUserId(String userId) {
        return requireAuthBody(() -> authServerClient.checkUserId(userId), "Check userId");
    }

    /**
     * 로컬 회원가입.
     * Auth Server {@code POST /auth/register} → SYS_USER INSERT (비밀번호 해시, mailAddress 부여).
     * 토큰은 발급하지 않음 — 가입 후 프론트가 /login 으로 이동.
     * 중복 userId 등은 Auth의 409를 {@link AuthServerException}으로 전파.
     */
    public UserResponse register(RegisterRequest request) {
        return requireAuthBody(() -> authServerClient.register(request).getBody(), "Register");
    }

    /**
     * SNS 로그인 직후 온보딩(userId 선택) 필요 여부.
     * JWT의 {@code sns_provider}/{@code sns_external_id} 로 Auth Server 등록 여부 조회.
     * <ul>
     *   <li>sns_* 클레임 없음 → 로컬/이미 정리된 토큰 → needsUserId=false</li>
     *   <li>등록 안 됨 → needsUserId=true (프론트 /onboarding)</li>
     *   <li>등록됨 → needsUserId=false + DB userId</li>
     * </ul>
     */
    public OnboardingStatusResponse getOnboardingStatus(Jwt jwt) {
        String snsProvider = jwt.getClaimAsString("sns_provider");
        String snsExternalId = jwt.getClaimAsString("sns_external_id");

        // 아이디/비밀번호 로그인이면 sns_*가 없음. 온보딩 화면 생략.
        if (!StringUtils.hasText(snsProvider) || !StringUtils.hasText(snsExternalId)) {
            return new OnboardingStatusResponse(false, jwt.getSubject());
        }

        // Auth에 이 SNS 계정이 있는지 조회.
        AuthServerClient.SocialUserStatus status =
                authServerClient.getSocialUserStatus(snsProvider, snsExternalId);

        if (!status.registered()) {
            // SYS_USER가 아직 없음. 프론트가 /onboarding에서 userId를 고르는 상태.
            return new OnboardingStatusResponse(true, null);
        }

        // 이미 등록된 userId.
        return new OnboardingStatusResponse(false, status.userId());
    }

    /**
     * SNS 최초 로그인 — 사용자가 고른 userId로 SYS_USER 생성 후 정식 토큰 재발급.
     * JWT sns_* 클레임 + 요청 userId 를 Auth Server {@code POST /auth/social/register} 로 넘기는 가입.
     * 응답 토큰의 sub 는 선택 userId, email 은 userId@도메인.
     */
    public TokenResponse completeSocialOnboarding(Jwt jwt, SocialCompleteRequest request) {
        String snsProvider = jwt.getClaimAsString("sns_provider");
        String snsExternalId = jwt.getClaimAsString("sns_external_id");
        String snsExternalEmail = jwt.getClaimAsString("sns_external_email");

        // SNS 클레임이 없으면 온보딩 불가.
        if (!StringUtils.hasText(snsProvider) || !StringUtils.hasText(snsExternalId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SNS claims missing in token");
        }

        // Auth에 userId 등록 후 정식 토큰.
        TokenResponse body = requireAuthBody(
                () -> authServerClient
                        .completeSocialRegistration(
                                snsProvider, snsExternalId, snsExternalEmail, request.userId())
                        .getBody(),
                "Social register");

        // access가 비면 가입 실패.
        if (!StringUtils.hasText(body.accessToken())) {
            throw new IllegalStateException("Social register failed: empty response from auth server");
        }

        return body;
    }

    /**
     * SNS 로그인 시작 — 브라우저를 Auth Server public URL의 prepare 로 302 redirect.
     * 프론트는 BFF {@code GET /api/auth/social/prepare/{provider}} 만 호출. Auth :9000 직접 호출 없음.
     */
    public void redirectToSocialPrepare(
            String provider,
            String state,
            String codeChallenge,
            String redirectUri,
            HttpServletResponse response) throws IOException {
        // Auth public URL로 302.
        String target = authServerClient.buildSocialPrepareRedirectUrl(
                provider, state, codeChallenge, redirectUri);
        response.sendRedirect(target);
    }

    /**
     * SNS OAuth callback — authorization_code + PKCE 를 access/refresh 로 교환.
     * Auth Server {@code POST /oauth2/token} (grant_type=authorization_code).
     */
    public TokenResponse exchangeToken(TokenExchangeRequest request) {
        // SNS 콜백 code + PKCE verifier → access/refresh.
        return requireOauthBody(
                () -> authServerClient.exchangeAuthorizationCode(request).getBody(), "Token exchange");
    }

    /**
     * access_token 갱신.
     * refresh_token 은 요청 body가 아니라 cookie에서 읽어 Controller가 넘기는 갱신.
     * Auth Server {@code POST /oauth2/token} (grant_type=refresh_token).
     */
    public TokenResponse refreshToken(String refreshToken) {
        // cookie에서 읽은 refresh로 새 access 발급.
        return requireOauthBody(() -> authServerClient.refreshToken(refreshToken).getBody(), "Token refresh");
    }

    /** login/register 등 Auth public API. 4xx는 status+body를 그대로 넘기는 예외 변환. */
    private <T> T requireAuthBody(Supplier<T> call, String action) {
        try {
            T body = call.get();

            // 빈 body는 실패.
            if (body == null) {
                throw new IllegalStateException(action + " failed: empty response from auth server");
            }

            return body;

        } catch (HttpStatusCodeException ex) {
            // Auth 4xx body를 프론트가 그대로 쓰게 전달.
            throw new AuthServerException(ex.getStatusCode(), ex.getResponseBodyAsString());
        }
    }

    /** /oauth2/token. 4xx는 IllegalStateException으로 감싸는 예외 변환. */
    private <T> T requireOauthBody(Supplier<T> call, String action) {
        try {
            T body = call.get();

            if (body == null) {
                throw new IllegalStateException(action + " failed: empty response from auth server");
            }

            return body;

        } catch (HttpStatusCodeException ex) {
            // 토큰 엔드포인트 실패는 상태 코드를 풀지 않고 메시지로 감쌈.
            throw new IllegalStateException(action + " failed: " + ex.getResponseBodyAsString(), ex);
        }
    }
}
