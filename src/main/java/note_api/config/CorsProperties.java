package note_api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** 프론트 origin 목록. {@code app.cors.allowed-origins}. */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {
    public CorsProperties {
        if (allowedOrigins == null) {
            allowedOrigins = List.of();
        }
    }
}
