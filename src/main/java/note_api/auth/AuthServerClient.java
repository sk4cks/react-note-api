package note_api.auth;

import note_api.auth.dto.LoginRequest;
import note_api.auth.dto.TokenExchangeRequest;
import note_api.auth.dto.TokenResponse;
import note_api.mail.MailGoogleNotLinkedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class AuthServerClient {

    private final RestTemplate restTemplate;
    private final String authServerBaseUrl;
    private final String authServerPublicUrl;
    private final String loginUrl;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final String internalApiKey;

    public AuthServerClient(
            RestTemplate restTemplate,
            @Value("${auth-server.base-url}") String authServerBaseUrl,
            @Value("${auth-server.public-url}") String authServerPublicUrl,
            @Value("${oauth2.client.client-id}") String clientId,
            @Value("${oauth2.client.client-secret}") String clientSecret,
            @Value("${auth-server.internal-api-key}") String internalApiKey) {
        this.restTemplate = restTemplate;
        this.authServerBaseUrl = authServerBaseUrl;
        this.authServerPublicUrl = authServerPublicUrl;
        this.loginUrl = authServerBaseUrl + "/auth/login";
        this.tokenUri = authServerBaseUrl + "/oauth2/token";
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.internalApiKey = internalApiKey;
    }

    /** SNS prepare — 브라우저 redirect (public-url) */
    public String buildSocialPrepareRedirectUrl(
            String provider, String state, String codeChallenge, String redirectUri) {
        return UriComponentsBuilder.fromUriString(authServerPublicUrl + "/auth/social/prepare/" + provider)
                .queryParam("state", state)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("redirect_uri", redirectUri)
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUriString();
    }

    public ResponseEntity<TokenResponse> login(LoginRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<LoginRequest> entity = new HttpEntity<>(request, headers);
        return restTemplate.postForEntity(loginUrl, entity, TokenResponse.class);
    }

    public ResponseEntity<TokenResponse> exchangeAuthorizationCode(TokenExchangeRequest request) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", request.code());
        form.add("redirect_uri", request.redirectUri());
        form.add("code_verifier", request.codeVerifier());
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, headers);

        return restTemplate.postForEntity(tokenUri, entity, TokenResponse.class);
    }

    public ResponseEntity<TokenResponse> refreshToken(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, headers);

        return restTemplate.postForEntity(tokenUri, entity, TokenResponse.class);
    }

    /** Auth Server에 저장된 Google Gmail access token (BFF → Gmail API) */
    public String fetchGoogleAccessToken(String principal) {
        String url = UriComponentsBuilder
                .fromUriString(authServerBaseUrl + "/auth/google/access-token")
                .queryParam("principal", principal)
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Api-Key", internalApiKey);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, Map.class);
            Map<?, ?> body = response.getBody();
            if (body == null || body.get("accessToken") == null) {
                throw new IllegalStateException("Google access token missing in auth server response");
            }
            return body.get("accessToken").toString();
        } catch (HttpClientErrorException.NotFound ex) {
            throw new MailGoogleNotLinkedException();
        }
    }
}
