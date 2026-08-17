package note_api.auth;

import note_api.auth.dto.LoginRequest;
import note_api.auth.dto.TokenExchangeRequest;
import note_api.auth.dto.TokenResponse;
import note_api.common.exception.ApiException;
import note_api.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * AuthServerClient 단위 테스트.
 * <p>
 * RestTemplate 실제 네트워크 호출 대신 MockRestServiceServer로 요청 URL, 헤더, form body를 검증.
 * AuthServiceTest가 "위임/예외 래핑"을 본다면, 여기서는 "Auth Server HTTP 요청 구성이 맞는지"를 본다.
 */
class AuthServerClientTest {

  private static final String AUTH_SERVER_BASE_URL = "http://localhost:9000";
  private static final String AUTH_SERVER_PUBLIC_URL = "http://localhost:9000";
  private static final String CLIENT_ID = "react-note-client";
  private static final String CLIENT_SECRET = "secret";
  private static final String INTERNAL_API_KEY = "internal-key";

  private RestTemplate restTemplate;
  private MockRestServiceServer server;
  private AuthServerClient authServerClient;

  @BeforeEach
  void setUp() {
    restTemplate = new RestTemplate();
    server = MockRestServiceServer.bindTo(restTemplate).build();
    authServerClient =
        new AuthServerClient(
            restTemplate,
            AUTH_SERVER_BASE_URL,
            AUTH_SERVER_PUBLIC_URL,
            CLIENT_ID,
            CLIENT_SECRET,
            INTERNAL_API_KEY);
  }

  /** SNS prepare URL — provider path + state/code_challenge/redirect_uri query string 인코딩 */
  @Test
  void buildSocialPrepareRedirectUrl_encodesQueryParams() {
    String url =
        authServerClient.buildSocialPrepareRedirectUrl(
            "google",
            "state-1",
            "challenge-1",
            "http://localhost:8080/oauth/callback?next=/mail");

    assertThat(url).startsWith("http://localhost:9000/auth/social/prepare/google?");
    assertThat(url).contains("state=state-1");
    assertThat(url).contains("code_challenge=challenge-1");
    // redirect_uri 전체 인코딩 형식은 Spring UriComponentsBuilder 동작에 맞춰 주요 구간만 검증
    assertThat(url).contains("redirect_uri=");
    assertThat(url).contains("oauth");
    assertThat(url).contains("callback");
    assertThat(url).contains("next");
  }

  /** 로컬 로그인 — /auth/login 으로 JSON body POST */
  @Test
  void login_postsJsonBodyToAuthServer() {
    server
        .expect(requestTo(AUTH_SERVER_BASE_URL + "/auth/login"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().json("{\"userId\":\"user1\",\"password\":\"1234\"}"))
        .andRespond(
            withSuccess(
                """
                {
                  "access_token": "access-1",
                  "token_type": "Bearer",
                  "expires_in": 3600,
                  "refresh_token": "refresh-1",
                  "scope": "openid"
                }
                """,
                MediaType.APPLICATION_JSON));

    TokenResponse result = authServerClient.login(new LoginRequest("user1", "1234")).getBody();

    assertThat(result)
        .isEqualTo(new TokenResponse("access-1", "Bearer", 3600L, "refresh-1", "openid"));
    server.verify();
  }

  @Test
  void register_postsJsonBodyToAuthServer() {
    server
        .expect(requestTo(AUTH_SERVER_BASE_URL + "/auth/register"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().json("{\"userId\":\"sk4cks\",\"password\":\"1234\"}"))
        .andRespond(
            withSuccess(
                """
                {
                  "userSeq": 1,
                  "userId": "sk4cks",
                  "mailAddress": "sk4cks@note.local",
                  "authProvider": "LOCAL",
                  "status": "ACTIVE"
                }
                """,
                MediaType.APPLICATION_JSON));

    var result =
        authServerClient.register(new note_api.auth.dto.RegisterRequest("sk4cks", "1234")).getBody();

    assertThat(result.userId()).isEqualTo("sk4cks");
    assertThat(result.mailAddress()).isEqualTo("sk4cks@note.local");
    server.verify();
  }

  /** authorization_code grant — form-urlencoded body에 code_verifier, client_id, client_secret 포함 */
  @Test
  void exchangeAuthorizationCode_postsFormBodyToTokenEndpoint() {
    server
        .expect(requestTo(AUTH_SERVER_BASE_URL + "/oauth2/token"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            header(HttpHeaders.CONTENT_TYPE, containsString(MediaType.APPLICATION_FORM_URLENCODED_VALUE)))
        .andExpect(content().string(containsString("grant_type=authorization_code")))
        .andExpect(content().string(containsString("code=auth-code")))
        .andExpect(
            content()
                .string(
                    containsString(
                        "redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Foauth%2Fcallback")))
        .andExpect(content().string(containsString("code_verifier=verifier")))
        .andExpect(content().string(containsString("client_id=" + CLIENT_ID)))
        .andExpect(content().string(containsString("client_secret=" + CLIENT_SECRET)))
        .andRespond(
            withSuccess(
                """
                {
                  "access_token": "access-2",
                  "token_type": "Bearer",
                  "expires_in": 3600,
                  "refresh_token": "refresh-2",
                  "scope": "openid profile"
                }
                """,
                MediaType.APPLICATION_JSON));

    TokenResponse result =
        authServerClient
            .exchangeAuthorizationCode(
                new TokenExchangeRequest(
                    "auth-code", "verifier", "http://localhost:8080/oauth/callback"))
            .getBody();

    assertThat(result)
        .isEqualTo(new TokenResponse("access-2", "Bearer", 3600L, "refresh-2", "openid profile"));
    server.verify();
  }

  /** refresh_token grant — form-urlencoded body에 refresh_token, client_id, client_secret 포함 */
  @Test
  void refreshToken_postsFormBodyToTokenEndpoint() {
    server
        .expect(requestTo(AUTH_SERVER_BASE_URL + "/oauth2/token"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            header(HttpHeaders.CONTENT_TYPE, containsString(MediaType.APPLICATION_FORM_URLENCODED_VALUE)))
        .andExpect(content().string(containsString("grant_type=refresh_token")))
        .andExpect(content().string(containsString("refresh_token=rt-123")))
        .andExpect(content().string(containsString("client_id=" + CLIENT_ID)))
        .andExpect(content().string(containsString("client_secret=" + CLIENT_SECRET)))
        .andRespond(
            withSuccess(
                """
                {
                  "access_token": "access-3",
                  "token_type": "Bearer",
                  "expires_in": 3600,
                  "refresh_token": "refresh-3",
                  "scope": "openid"
                }
                """,
                MediaType.APPLICATION_JSON));

    TokenResponse result = authServerClient.refreshToken("rt-123").getBody();

    assertThat(result)
        .isEqualTo(new TokenResponse("access-3", "Bearer", 3600L, "refresh-3", "openid"));
    server.verify();
  }

  /** Gmail access token 조회 — 내부 API key 헤더와 principal query param 전달 */
  @Test
  void fetchGoogleAccessToken_sendsInternalHeaderAndReturnsToken() {
    server
        .expect(
            requestTo(
                containsString(
                    AUTH_SERVER_BASE_URL + "/auth/google/access-token?principal=sk4cks")))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Internal-Api-Key", INTERNAL_API_KEY))
        .andRespond(withSuccess("{\"accessToken\":\"google-token-1\"}", MediaType.APPLICATION_JSON));

    String token = authServerClient.fetchGoogleAccessToken("sk4cks@gmail.com");

    assertThat(token).isEqualTo("google-token-1");
    server.verify();
  }

  /** accessToken 필드가 없으면 Gmail API 호출 전에 즉시 실패 */
  @Test
  void fetchGoogleAccessToken_throwsWhenAccessTokenMissing() {
    server
        .expect(
            requestTo(
                containsString(
                    AUTH_SERVER_BASE_URL + "/auth/google/access-token?principal=sk4cks")))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> authServerClient.fetchGoogleAccessToken("sk4cks@gmail.com"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Google access token missing");
  }

  /** Auth Server 404 는 "Google 계정 미연동" 도메인 예외로 변환 */
  @Test
  void fetchGoogleAccessToken_throwsMailGoogleNotLinkedWhenNotFound() {
    server
        .expect(
            requestTo(
                containsString(
                    AUTH_SERVER_BASE_URL + "/auth/google/access-token?principal=sk4cks")))
        .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

    assertThatThrownBy(() -> authServerClient.fetchGoogleAccessToken("sk4cks@gmail.com"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Google login with Gmail scope is required")
        .extracting(ex -> ((ApiException) ex).getErrorCode())
        .isEqualTo(ErrorCode.MAIL_GOOGLE_NOT_LINKED);
  }

  @Test
  void fetchMailboxCredentials_sendsInternalHeaderAndReturnsBody() {
    server
        .expect(requestTo(AUTH_SERVER_BASE_URL + "/auth/users/sk4cks/mailbox"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Internal-Api-Key", INTERNAL_API_KEY))
        .andRespond(
            withSuccess(
                """
                {
                  "mailAddress":"sk4cks@note.local",
                  "password":"secret",
                  "imapHost":"127.0.0.1",
                  "imapPort":993,
                  "smtpHost":"127.0.0.1",
                  "smtpPort":587
                }
                """,
                MediaType.APPLICATION_JSON));

    var creds = authServerClient.fetchMailboxCredentials("sk4cks");

    assertThat(creds.mailAddress()).isEqualTo("sk4cks@note.local");
    assertThat(creds.password()).isEqualTo("secret");
    assertThat(creds.imapPort()).isEqualTo(993);
    server.verify();
  }

  @Test
  void fetchMailboxCredentials_throwsWhenNotFound() {
    server
        .expect(requestTo(AUTH_SERVER_BASE_URL + "/auth/users/missing/mailbox"))
        .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

    assertThatThrownBy(() -> authServerClient.fetchMailboxCredentials("missing"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("missing")
        .extracting(ex -> ((ApiException) ex).getErrorCode())
        .isEqualTo(ErrorCode.MAIL_MAILBOX_NOT_FOUND);
  }
}
