package note_api.auth.dto;

public record TokenExchangeRequest(
        String code,
        String codeVerifier,
        String redirectUri
) {}
