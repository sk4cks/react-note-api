package note_api.auth;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;

import java.io.IOException;

@Service
public class AuthService {

    private final AuthServerClient authServerClient;

    public AuthService(AuthServerClient authServerClient) {
        this.authServerClient = authServerClient;
    }

    public TokenResponse login(LoginRequest request) {
        try {
            ResponseEntity<TokenResponse> response = authServerClient.login(request);
            TokenResponse body = response.getBody();
            if (body == null) {
                throw new IllegalStateException("Login failed: empty response from auth server");
            }
            return body;
        } catch (HttpStatusCodeException ex) {
            throw new IllegalStateException("Login failed: " + ex.getResponseBodyAsString(), ex);
        }
    }

    public void redirectToSocialPrepare(
            String provider,
            String state,
            String codeChallenge,
            String redirectUri,
            HttpServletResponse response) throws IOException {
        String target = authServerClient.buildSocialPrepareRedirectUrl(
                provider, state, codeChallenge, redirectUri);
        response.sendRedirect(target);
    }

    public TokenResponse exchangeToken(TokenExchangeRequest request) {
        try {
            ResponseEntity<TokenResponse> response = authServerClient.exchangeAuthorizationCode(request);
            TokenResponse body = response.getBody();
            if (body == null) {
                throw new IllegalStateException("Token exchange failed: empty response from auth server");
            }
            return body;
        } catch (HttpStatusCodeException ex) {
            throw new IllegalStateException("Token exchange failed: " + ex.getResponseBodyAsString(), ex);
        }
    }

    public TokenResponse refreshToken(String refreshToken) {
        try {
            ResponseEntity<TokenResponse> response = authServerClient.refreshToken(refreshToken);
            TokenResponse body = response.getBody();
            if (body == null) {
                throw new IllegalStateException("Token refresh failed: empty response from auth server");
            }
            return body;
        } catch (HttpStatusCodeException ex) {
            throw new IllegalStateException("Token refresh failed: " + ex.getResponseBodyAsString(), ex);
        }
    }
}
