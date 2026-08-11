package note_api.auth;

import note_api.config.RefreshTokenCookieProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;

@Service
public class RefreshTokenCookieService {

    private final RefreshTokenCookieProperties properties;

    public RefreshTokenCookieService(RefreshTokenCookieProperties properties) {
        this.properties = properties;
    }

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

    public void writeRefreshToken(HttpServletResponse response, String refreshToken) {
        if (!StringUtils.hasText(refreshToken)) {
            return;
        }
        response.addHeader(
                "Set-Cookie",
                buildCookie(refreshToken, Duration.ofDays(properties.maxAgeDays())).toString());
    }

    public void clearRefreshToken(HttpServletResponse response) {
        response.addHeader("Set-Cookie", buildCookie("", Duration.ZERO).toString());
    }

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
