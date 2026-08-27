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

    public RefreshTokenCookieService(RefreshTokenCookieProperties properties) {
        this.properties = properties;
    }

    /** 요청 cookie에서 refresh_token 값을 읽는다. */
    public String readRefreshToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
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

    /** 로그인·갱신 응답에 Set-Cookie로 넣는다. 값이 비면 아무 것도 안 한다. */
    public void writeRefreshToken(HttpServletResponse response, String refreshToken) {
        if (!StringUtils.hasText(refreshToken)) {
            return;
        }
        response.addHeader(
                "Set-Cookie",
                buildCookie(refreshToken, Duration.ofDays(properties.maxAgeDays())).toString());
    }

    /** maxAge=0 으로 cookie를 지운다. */
    public void clearRefreshToken(HttpServletResponse response) {
        response.addHeader("Set-Cookie", buildCookie("", Duration.ZERO).toString());
    }

    /** HttpOnly + SameSite. JS에서 읽지 못한다. */
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
