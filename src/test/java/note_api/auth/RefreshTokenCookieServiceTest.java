package note_api.auth;

import note_api.config.RefreshTokenCookieProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RefreshTokenCookieService 단위 테스트.
 * <p>
 * HttpOnly refresh cookie 읽기/쓰기/삭제 — Properties는 실제 객체 사용 (설정 값 바인딩만 담당).
 * Servlet API는 @Mock으로 대체.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenCookieServiceTest {

  private static final RefreshTokenCookieProperties PROPERTIES =
      new RefreshTokenCookieProperties("refresh_token", true, "Lax", 30);

  @Mock private HttpServletRequest request;
  @Mock private HttpServletResponse response;

  private RefreshTokenCookieService cookieService;

  @BeforeEach
  void setUp() {
    cookieService = new RefreshTokenCookieService(PROPERTIES);
  }

  /** cookie 배열이 없으면 null */
  @Test
  void readRefreshToken_returnsNull_whenNoCookies() {
    when(request.getCookies()).thenReturn(null);

    assertThat(cookieService.readRefreshToken(request)).isNull();
  }

  /** 이름이 다르거나 값이 비어 있으면 null */
  @Test
  void readRefreshToken_returnsNull_whenMatchingCookieMissing() {
    when(request.getCookies())
        .thenReturn(new Cookie[] {new Cookie("other", "x"), new Cookie("refresh_token", "")});

    assertThat(cookieService.readRefreshToken(request)).isNull();
  }

  /** 설정된 cookie 이름·값이 있으면 refresh token 반환 */
  @Test
  void readRefreshToken_returnsValue_whenCookiePresent() {
    when(request.getCookies())
        .thenReturn(new Cookie[] {new Cookie("refresh_token", "rt-secret")});

    assertThat(cookieService.readRefreshToken(request)).isEqualTo("rt-secret");
  }

  /** refresh token이 있으면 Set-Cookie 헤더에 HttpOnly·SameSite·Max-Age 설정 */
  @Test
  void writeRefreshToken_setsCookieHeader_whenTokenPresent() {
    cookieService.writeRefreshToken(response, "rt-secret");

    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(response).addHeader(eq("Set-Cookie"), captor.capture());

    String header = captor.getValue();
    assertThat(header).contains("refresh_token=rt-secret");
    assertThat(header).contains("HttpOnly");
    assertThat(header).contains("SameSite=Lax");
    assertThat(header).contains("Max-Age=2592000"); // 30 days
  }

  /** null/blank refresh token이면 헤더 추가 안 함 */
  @Test
  void writeRefreshToken_doesNothing_whenTokenBlank() {
    cookieService.writeRefreshToken(response, "  ");

    verifyNoInteractions(response);
  }

  /** 로그아웃 — Max-Age=0 으로 cookie 삭제 */
  @Test
  void clearRefreshToken_setsExpiredCookie() {
    cookieService.clearRefreshToken(response);

    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(response).addHeader(eq("Set-Cookie"), captor.capture());

    String header = captor.getValue();
    assertThat(header).contains("refresh_token=");
    assertThat(header).contains("Max-Age=0");
  }
}
