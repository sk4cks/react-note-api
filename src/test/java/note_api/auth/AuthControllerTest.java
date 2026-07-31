package note_api.auth;

import note_api.auth.dto.LoginRequest;
import note_api.auth.dto.RegisterRequest;
import note_api.auth.dto.TokenExchangeRequest;
import note_api.auth.dto.TokenResponse;
import note_api.auth.dto.UserResponse;
import jakarta.servlet.http.HttpServletResponse;
import note_api.common.exception.GlobalExceptionHandler;
import note_api.config.CorsProperties;
import note_api.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AuthController 슬라이스 테스트.
 * <p>
 * @SpringBootTest(전체 앱 기동) 대신 @WebMvcTest를 쓰는 이유:
 * - AuthController + Security 설정만 올려서 빠르게 검증
 * - Auth Server(:9000), GmailClient 등 외부 연동 Bean은 올리지 않음
 * <p>
 * AuthService·RefreshTokenCookieService는 @MockBean — HTTP 요청/응답 계약만 검증.
 * /api/auth/** 는 SecurityConfig에서 permitAll 이므로 JWT 없이 호출 가능.
 */
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@EnableConfigurationProperties(CorsProperties.class)
@TestPropertySource(properties = "app.cors.allowed-origins=http://localhost:8080")
class AuthControllerTest {

  @Autowired private MockMvc mockMvc;

  /** Auth Server 호출·토큰 교환 로직은 여기서 검증하지 않음 */
  @MockBean private AuthService authService;

  /** HttpOnly cookie 읽기/쓰기 — MockMvc 응답 헤더 검증 대신 호출 여부만 verify */
  @MockBean private RefreshTokenCookieService refreshTokenCookieService;

  /** 로컬 로그인: access_token은 JSON body, refresh_token은 body에 노출하지 않고 cookie로만 전달 */
  @Test
  void login_returnsAccessTokenWithoutRefreshTokenInBody() throws Exception {
    TokenResponse tokens =
        new TokenResponse("access-123", "Bearer", 3600L, "refresh-secret", "openid");
    when(authService.login(any(LoginRequest.class))).thenReturn(tokens);

    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"user1\",\"password\":\"1234\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.access_token").value("access-123"))
        .andExpect(jsonPath("$.token_type").value("Bearer"))
        .andExpect(jsonPath("$.expires_in").value(3600))
        .andExpect(jsonPath("$.scope").value("openid"))
        .andExpect(jsonPath("$.refresh_token").doesNotExist());

    verify(refreshTokenCookieService).writeRefreshToken(any(), eq("refresh-secret"));
  }

  @Test
  void register_returnsCreated() throws Exception {
    UserResponse user =
        new UserResponse(1L, "sk4cks", "sk4cks@note.local", "LOCAL", "ACTIVE");
    when(authService.register(any(RegisterRequest.class))).thenReturn(user);

    mockMvc
        .perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"sk4cks\",\"password\":\"1234\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.userId").value("sk4cks"))
        .andExpect(jsonPath("$.mailAddress").value("sk4cks@note.local"));
  }

  @Test
  void register_returnsBadRequest_whenUserIdInvalid() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"bad-id!\",\"password\":\"1234\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.message").value("아이디는 영문, 숫자, 밑줄만 사용할 수 있습니다"))
        .andExpect(jsonPath("$.errors.userId").value("아이디는 영문, 숫자, 밑줄만 사용할 수 있습니다"));
  }

  /** SNS OAuth callback 후 authorization code → access_token 교환 (PKCE codeVerifier 포함) */
  @Test
  void token_returnsAccessTokenWithoutRefreshTokenInBody() throws Exception {
    TokenResponse tokens =
        new TokenResponse("access-xyz", "Bearer", 3600L, "refresh-xyz", "openid profile");
    when(authService.exchangeToken(any(TokenExchangeRequest.class))).thenReturn(tokens);

    mockMvc
        .perform(
            post("/api/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"code":"auth-code","codeVerifier":"verifier","redirectUri":"http://localhost:8080/oauth/callback"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.access_token").value("access-xyz"))
        .andExpect(jsonPath("$.scope").value("openid profile"))
        .andExpect(jsonPath("$.refresh_token").doesNotExist());

    verify(refreshTokenCookieService).writeRefreshToken(any(), eq("refresh-xyz"));
  }

  /** refresh_token은 HttpOnly cookie — JS에서 보내지 않으면 401 */
  @Test
  void refresh_returnsUnauthorized_whenCookieMissing() throws Exception {
    when(refreshTokenCookieService.readRefreshToken(any())).thenReturn(null);

    mockMvc.perform(post("/api/auth/refresh")).andExpect(status().isUnauthorized());
  }

  /** cookie의 refresh_token으로 새 access_token 발급, 갱신된 refresh는 다시 cookie에 저장 */
  @Test
  void refresh_returnsAccessToken_whenCookiePresent() throws Exception {
    when(refreshTokenCookieService.readRefreshToken(any())).thenReturn("rt-123");
    when(authService.refreshToken("rt-123"))
        .thenReturn(new TokenResponse("new-access", "Bearer", 3600L, "new-rt", "openid"));

    mockMvc
        .perform(post("/api/auth/refresh"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.access_token").value("new-access"))
        .andExpect(jsonPath("$.refresh_token").doesNotExist());

    verify(refreshTokenCookieService).writeRefreshToken(any(), eq("new-rt"));
  }

  /** 로그아웃 시 refresh cookie 삭제 (204 No Content) */
  @Test
  void logout_clearsRefreshCookie() throws Exception {
    mockMvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());

    verify(refreshTokenCookieService).clearRefreshToken(any());
  }

  /**
   * SNS 로그인 시작: BFF가 Auth Server prepare URL로 302 redirect.
   * <p>
   * 실행 순서:
   * 1. doAnswer — 스텁 등록만 함 (람다는 아직 실행 안 됨)
   * 2. mockMvc.perform — GET 요청 → AuthController → authService.redirectToSocialPrepare 호출
   * 3. 그 호출 시점에 doAnswer 람다 실행 → response.sendRedirect (302 설정)
   * 4. andExpect — 최종 응답이 302 + Location 인지 검증
   * <p>
   * authService는 @MockBean 이라 기본적으로 아무 동작도 안 함.
   * redirectToSocialPrepare는 반환값 없이 response를 바꾸는 void 메서드라
   * when().thenReturn() 대신 doAnswer로 sendRedirect를 시뮬레이션함.
   */
  @Test
  void socialPrepare_redirectsToAuthServer() throws Exception {
    // 나중에 redirectToSocialPrepare가 호출되면 아래 람다 실행 (지금은 등록만)
    doAnswer(
            invocation -> {
              // 파라미터: provider(0), state(1), codeChallenge(2), redirectUri(3), response(4)
              HttpServletResponse response = invocation.getArgument(4);
              response.sendRedirect("http://localhost:9000/auth/social/prepare/google");
              return null; // void 메서드
            })
        .when(authService)
        .redirectToSocialPrepare(
            eq("google"),
            eq("state-1"),
            eq("challenge"),
            eq("http://localhost:8080/oauth/callback"),
            any()); // HttpServletResponse — MockMvc가 넘기는 객체

    mockMvc
        .perform(
            get("/api/auth/social/prepare/google")
                .param("state", "state-1")
                .param("code_challenge", "challenge")
                .param("redirect_uri", "http://localhost:8080/oauth/callback"))
        .andExpect(status().isFound())
        .andExpect(redirectedUrl("http://localhost:9000/auth/social/prepare/google"));
  }
}
