package note_api.auth;

import note_api.auth.dto.LoginRequest;
import note_api.auth.dto.TokenExchangeRequest;
import note_api.auth.dto.TokenResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthService 단위 테스트.
 * <p>
 * AuthServerClient를 @Mock — Auth Server HTTP 호출 없이 위임·에러 변환만 검증.
 * HTTP 계약은 AuthControllerTest에서 검증.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

  private static final LoginRequest LOGIN_REQUEST = new LoginRequest("user1", "1234");
  private static final TokenExchangeRequest TOKEN_REQUEST =
      new TokenExchangeRequest("auth-code", "verifier", "http://localhost:8080/oauth/callback");
  private static final TokenResponse TOKEN_RESPONSE =
      new TokenResponse("access-1", "Bearer", 3600L, "refresh-1", "openid");

  @Mock private AuthServerClient authServerClient;
  @Mock private HttpServletResponse httpServletResponse;

  @InjectMocks private AuthService authService;

  /** 로컬 로그인 — Auth Server 응답 body를 그대로 반환 */
  @Test
  void login_returnsToken_whenAuthServerSucceeds() {
    when(authServerClient.login(LOGIN_REQUEST)).thenReturn(ResponseEntity.ok(TOKEN_RESPONSE));

    TokenResponse result = authService.login(LOGIN_REQUEST);

    assertThat(result).isEqualTo(TOKEN_RESPONSE);
    verify(authServerClient).login(LOGIN_REQUEST);
  }

  /** Auth Server가 빈 body면 IllegalStateException */
  @Test
  void login_throws_whenResponseBodyEmpty() {
    when(authServerClient.login(LOGIN_REQUEST)).thenReturn(ResponseEntity.ok(null));

    assertThatThrownBy(() -> authService.login(LOGIN_REQUEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Login failed: empty response");
  }

  /** 4xx/5xx 응답은 IllegalStateException으로 래핑 (본문 메시지 포함) */
  @Test
  void login_throws_whenAuthServerReturnsError() {
    HttpClientErrorException ex =
        HttpClientErrorException.create(
            HttpStatus.UNAUTHORIZED,
            "Unauthorized",
            HttpHeaders.EMPTY,
            "bad credentials".getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8);
    when(authServerClient.login(LOGIN_REQUEST)).thenThrow(ex);

    assertThatThrownBy(() -> authService.login(LOGIN_REQUEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Login failed: bad credentials")
        .hasCause(ex);
  }

  /** SNS OAuth code → token 교환 */
  @Test
  void exchangeToken_returnsToken_whenAuthServerSucceeds() {
    when(authServerClient.exchangeAuthorizationCode(TOKEN_REQUEST))
        .thenReturn(ResponseEntity.ok(TOKEN_RESPONSE));

    TokenResponse result = authService.exchangeToken(TOKEN_REQUEST);

    assertThat(result).isEqualTo(TOKEN_RESPONSE);
  }

  @Test
  void exchangeToken_throws_whenResponseBodyEmpty() {
    when(authServerClient.exchangeAuthorizationCode(TOKEN_REQUEST))
        .thenReturn(ResponseEntity.ok(null));

    assertThatThrownBy(() -> authService.exchangeToken(TOKEN_REQUEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Token exchange failed: empty response");
  }

  /** refresh_token grant */
  @Test
  void refreshToken_returnsToken_whenAuthServerSucceeds() {
    when(authServerClient.refreshToken("rt-123")).thenReturn(ResponseEntity.ok(TOKEN_RESPONSE));

    TokenResponse result = authService.refreshToken("rt-123");

    assertThat(result).isEqualTo(TOKEN_RESPONSE);
    verify(authServerClient).refreshToken("rt-123");
  }

  @Test
  void refreshToken_throws_whenAuthServerReturnsError() {
    HttpClientErrorException ex =
        HttpClientErrorException.create(
            HttpStatus.BAD_REQUEST,
            "Bad Request",
            HttpHeaders.EMPTY,
            "invalid_grant".getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8);
    when(authServerClient.refreshToken("rt-123")).thenThrow(ex);

    assertThatThrownBy(() -> authService.refreshToken("rt-123"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Token refresh failed: invalid_grant");
  }

  /**
   * SNS prepare — Auth Server redirect URL 생성 후 response.sendRedirect.
   * redirectToSocialPrepare는 void + response 부수 효과라 MailControllerTest의 doAnswer와 같은 패턴.
   */
  @Test
  void redirectToSocialPrepare_sendsRedirectToAuthServerUrl() throws Exception {
    String targetUrl = "http://localhost:9000/auth/social/prepare/google?state=s1";
    when(authServerClient.buildSocialPrepareRedirectUrl(
            "google", "s1", "challenge", "http://localhost:8080/oauth/callback"))
        .thenReturn(targetUrl);

    authService.redirectToSocialPrepare(
        "google", "s1", "challenge", "http://localhost:8080/oauth/callback", httpServletResponse);

    verify(authServerClient)
        .buildSocialPrepareRedirectUrl(
            "google", "s1", "challenge", "http://localhost:8080/oauth/callback");
    verify(httpServletResponse).sendRedirect(targetUrl);
  }
}
