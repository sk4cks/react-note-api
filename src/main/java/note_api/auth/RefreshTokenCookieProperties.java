package note_api.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** refresh_token cookie 이름·Secure·SameSite·만료. {@code app.auth.refresh-cookie}. */
@ConfigurationProperties(prefix = "app.auth.refresh-cookie")
public record RefreshTokenCookieProperties(
        String name,
        boolean secure,
        String sameSite,
        int maxAgeDays
) {
    public RefreshTokenCookieProperties {
        if (name == null || name.isBlank()) {
            name = "refresh_token";
        }
        if (sameSite == null || sameSite.isBlank()) {
            sameSite = "Lax";
        }
        if (maxAgeDays <= 0) {
            maxAgeDays = 30;
        }
    }
}
