package note_api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
