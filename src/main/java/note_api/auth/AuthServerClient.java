package note_api.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Component
public class AuthServerClient {

    private final RestTemplate restTemplate;
    private final String loginUrl;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;

    public AuthServerClient(
            RestTemplate restTemplate,
            @Value("${auth-server.base-url}") String authServerBaseUrl,
            @Value("${auth-server.token-uri}") String tokenUri,
            @Value("${oauth2.client.client-id}") String clientId,
            @Value("${oauth2.client.client-secret}") String clientSecret) {
        this.restTemplate = restTemplate;
        this.loginUrl = authServerBaseUrl + "/auth/login";
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
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
}
