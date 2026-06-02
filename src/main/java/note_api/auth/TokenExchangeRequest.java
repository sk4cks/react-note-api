package note_api.auth;

public record TokenExchangeRequest(
        String code,
        String codeVerifier,
        String redirectUri
) {}
