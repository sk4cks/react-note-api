package note_api.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;

/** HttpOnly refresh_token cookie 읽기/쓰기/삭제. */
@Service
public class RefreshTokenCookieService {

    private final RefreshTokenCookieProperties properties;

    /** refresh cookie 이름·경로·SameSite 설정 주입. */
    public RefreshTokenCookieService(RefreshTokenCookieProperties properties) {
        this.properties = properties;
    }

    /** 요청 cookie에서 refresh_token 값 조회. */
    public String readRefreshToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();

        // cookie가 없으면 refresh 없음.
        if (cookies == null) {
            return null;
        }

        for (Cookie cookie : cookies) {
            if (properties.name().equals(cookie.getName())
                    && StringUtils.hasText(cookie.getValue())) {
                return cookie.getValue();
            }
        }

        return null;
    }

    /** 로그인·갱신 응답에 Set-Cookie로 저장. 값이 비면 생략. */
    public void writeRefreshToken(HttpServletResponse response, String refreshToken) {
        if (!StringUtils.hasText(refreshToken)) {
            // 빈 토큰은 cookie를 덮지 않음.
            return;
        }

        response.addHeader(
                "Set-Cookie",
                buildCookie(refreshToken, Duration.ofDays(properties.maxAgeDays())).toString());
    }

    /** maxAge=0 으로 cookie 삭제. */
    public void clearRefreshToken(HttpServletResponse response) {
        response.addHeader("Set-Cookie", buildCookie("", Duration.ZERO).toString());
    }

    /** HttpOnly + SameSite. JS에서 읽지 못하는 cookie. */
    private ResponseCookie buildCookie(String value, Duration maxAge) {
        return ResponseCookie.from(properties.name(), value)
                .httpOnly(true)
                .secure(properties.secure())
                .path("/")
                .maxAge(maxAge)
                .sameSite(properties.sameSite())
                .build();
    }
}
