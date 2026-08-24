package note_api.auth;

import note_api.auth.dto.LoginRequest;
import note_api.auth.dto.MailboxCredentialsResponse;
import note_api.auth.dto.RegisterRequest;
import note_api.auth.dto.SocialUserStatusResponse;
import note_api.auth.dto.TokenExchangeRequest;
import note_api.auth.dto.TokenResponse;
import note_api.auth.dto.UserIdAvailabilityResponse;
import note_api.auth.dto.UserResponse;
import note_api.common.exception.ApiException;
import note_api.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Auth Server HTTP 클라이언트 (BFF → Auth).
 * <p>
 * 프론트는 Auth Server(:9000)를 직접 호출하지 않고, 이 클라이언트를 통해서만 접근한다.
 * <ul>
 *   <li>{@code auth-server.base-url} — Pod/서버 간 호출 (로컬: localhost:9000, k8s: ClusterIP)</li>
 *   <li>{@code auth-server.public-url} — 브라우저 redirect 용 (nip.io 등 외부 URL)</li>
 * </ul>
 * Internal API({@code X-Internal-Api-Key})는 SNS 상태/등록, Gmail 토큰 조회에만 사용한다.
 * login/register/token 은 Auth의 public {@code /auth/**}, {@code /oauth2/token} 경로.
 */
@Component
public class AuthServerClient {

    private final RestTemplate restTemplate;
    private final String authServerBaseUrl;
    private final String authServerPublicUrl;
    private final String loginUrl;
    private final String registerUrl;
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
        this.registerUrl = authServerBaseUrl + "/auth/register";
        this.tokenUri = authServerBaseUrl + "/oauth2/token";
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.internalApiKey = internalApiKey;
    }

    /**
     * 아이디 사용 가능 여부 — {@code GET /auth/check-userid}.
     */
    public UserIdAvailabilityResponse checkUserId(String userId) {
        String url = UriComponentsBuilder
                .fromUriString(authServerBaseUrl + "/auth/check-userid")
                .queryParam("userId", userId)
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUriString();

        return restTemplate.getForObject(url, UserIdAvailabilityResponse.class);
    }

    /**
     * SNS 로그인 시작 URL 생성 (브라우저 302 Location).
     * Auth Server {@code GET /auth/social/prepare/{provider}} — PKCE state/code_challenge/redirect_uri 전달.
     * public-url 을 쓰는 이유: 브라우저가 직접 Auth로 가야 하므로 ClusterIP가 아닌 외부 URL.
     */
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

    /**
     * 로컬 로그인 — {@code POST /auth/login} (JSON).
     * 응답에 access_token + refresh_token 포함.
     */
    public ResponseEntity<TokenResponse> login(LoginRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<LoginRequest> entity = new HttpEntity<>(request, headers);

        return restTemplate.postForEntity(loginUrl, entity, TokenResponse.class);
    }

    /**
     * 로컬 회원가입 — {@code POST /auth/register} (JSON).
     * Auth Server가 SYS_USER INSERT 후 UserResponse 반환 (토큰 없음).
     */
    public ResponseEntity<UserResponse> register(RegisterRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<RegisterRequest> entity = new HttpEntity<>(request, headers);

        return restTemplate.postForEntity(registerUrl, entity, UserResponse.class);
    }

    /**
     * SNS 계정 SYS_USER 등록 여부 — {@code GET /auth/social/users/status} (internal).
     * JWT {@code sns_provider}/{@code sns_external_id} 로 AUTH_PROVIDER+EXTERNAL_ID 조회.
     */
    public SocialUserStatus getSocialUserStatus(String provider, String externalId) {
        String url = UriComponentsBuilder
                .fromUriString(authServerBaseUrl + "/auth/social/users/status")
                .queryParam("provider", provider)
                .queryParam("externalId", externalId)
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Api-Key", internalApiKey);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<SocialUserStatusResponse> response = restTemplate.exchange(
                url, HttpMethod.GET, entity, SocialUserStatusResponse.class);
        SocialUserStatusResponse body = response.getBody();
        if (body == null) {
            return new SocialUserStatus(false, null);
        }

        return new SocialUserStatus(body.registered(), body.userId());
    }

    /** Auth Server social status 응답 요약 */
    public record SocialUserStatus(boolean registered, String userId) {}

    /**
     * SNS 온보딩 완료 — {@code POST /auth/social/register} (internal).
     * SYS_USER INSERT(AUTH_PROVIDER+EXTERNAL_ID) 후 정식 access/refresh 토큰 발급.
     */
    public ResponseEntity<TokenResponse> completeSocialRegistration(
            String provider, String externalId, String externalEmail, String userId) {
        String url = authServerBaseUrl + "/auth/social/register";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Api-Key", internalApiKey);

        Map<String, String> body = Map.of(
                "provider", provider,
                "externalId", externalId,
                "externalEmail", externalEmail != null ? externalEmail : "",
                "userId", userId);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);

        return restTemplate.exchange(url, HttpMethod.POST, entity, TokenResponse.class);
    }

    /**
     * authorization_code → 토큰 교환 — {@code POST /oauth2/token}.
     * SPA client_id/secret + PKCE code_verifier 를 form-urlencoded 로 전송.
     */
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

    /**
     * refresh_token grant — {@code POST /oauth2/token}.
     * cookie에서 읽은 refresh_token으로 새 access(및 갱신 refresh) 발급.
     */
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

    /**
     * Google Gmail API용 access token 조회 — {@code GET /auth/google/access-token} (internal).
     * Auth Server oauth2Login 시 저장해 둔 AuthorizedClient 를 principal 기준으로 조회·갱신.
     * 404 이면 Google/Gmail 미연동 → {@link ApiException}({@link ErrorCode#MAIL_GOOGLE_NOT_LINKED}).
     */
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
            ResponseEntity<Map<String, String>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            Map<String, String> body = response.getBody();
            String accessToken = body != null ? body.get("accessToken") : null;
            if (!StringUtils.hasText(accessToken)) {
                throw new IllegalStateException("Google access token missing in auth server response");
            }

            return accessToken;

        } catch (HttpClientErrorException.NotFound ex) {
            throw new ApiException(ErrorCode.MAIL_GOOGLE_NOT_LINKED);
        }
    }

    /**
     * Mailcow IMAP/SMTP 자격 조회 — {@code GET /auth/users/{userId}/mailbox} (internal).
     * 404 이면 메일함 비밀번호 미저장 또는 사용자 없음 → {@link ApiException}({@link ErrorCode#MAIL_MAILBOX_NOT_FOUND}).
     */
    public MailboxCredentialsResponse fetchMailboxCredentials(String userId) {
        String url = authServerBaseUrl + "/auth/users/" + userId + "/mailbox";

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Api-Key", internalApiKey);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<MailboxCredentialsResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, MailboxCredentialsResponse.class);

            MailboxCredentialsResponse body = response.getBody();

            if (body == null || !StringUtils.hasText(body.mailAddress()) || !StringUtils.hasText(body.password())) {
                throw new IllegalStateException("Mailbox credentials missing in auth server response");
            }

            return body;

        } catch (HttpClientErrorException.NotFound ex) {
            throw new ApiException(ErrorCode.MAIL_MAILBOX_NOT_FOUND, userId);
        }
    }

    private HttpHeaders internalHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Api-Key", internalApiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        return headers;
    }

    private String userPath(String userId, String suffix) {
        return authServerBaseUrl + "/auth/users/" + userId + suffix;
    }

    /** RestTemplate.exchange(String)은 Hangul을 한 번 더 인코딩하므로 URI를 넘긴다. */
    private URI userQueryUri(String userId, String suffix, String q) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(userPath(userId, suffix));
        if (StringUtils.hasText(q)) {
            builder.queryParam("q", q);
        }

        return builder.build().encode(StandardCharsets.UTF_8).toUri();
    }

    public List<note_api.contact.dto.ContactResponse> listContacts(String userId, String q) {
        ResponseEntity<List<note_api.contact.dto.ContactResponse>> response = restTemplate.exchange(
                userQueryUri(userId, "/contacts", q),
                HttpMethod.GET,
                new HttpEntity<>(internalHeaders()),
                new ParameterizedTypeReference<>() {});

        return response.getBody() == null ? List.of() : response.getBody();
    }

    public note_api.contact.dto.ContactResponse createContact(String userId, Map<String, Object> body) {
        return restTemplate
                .exchange(
                        userPath(userId, "/contacts"),
                        HttpMethod.POST,
                        new HttpEntity<>(body, internalHeaders()),
                        note_api.contact.dto.ContactResponse.class)
                .getBody();
    }

    public void deleteContact(String userId, Long contactId) {
        restTemplate.exchange(
                userPath(userId, "/contacts/" + contactId),
                HttpMethod.DELETE,
                new HttpEntity<>(internalHeaders()),
                Void.class);
    }

    public List<note_api.contact.dto.RecipientSuggestItem> suggestContacts(String userId, String q) {
        ResponseEntity<List<note_api.contact.dto.RecipientSuggestItem>> response = restTemplate.exchange(
                userQueryUri(userId, "/contacts/suggest", q),
                HttpMethod.GET,
                new HttpEntity<>(internalHeaders()),
                new ParameterizedTypeReference<>() {});

        return response.getBody() == null ? List.of() : response.getBody();
    }

    public List<note_api.contact.dto.ContactGroupResponse> listContactGroups(String userId) {
        ResponseEntity<List<note_api.contact.dto.ContactGroupResponse>> response = restTemplate.exchange(
                userPath(userId, "/contact-groups"),
                HttpMethod.GET,
                new HttpEntity<>(internalHeaders()),
                new ParameterizedTypeReference<>() {});

        return response.getBody() == null ? List.of() : response.getBody();
    }

    public note_api.contact.dto.ContactGroupResponse createContactGroup(
            String userId, Map<String, Object> body) {
        return restTemplate
                .exchange(
                        userPath(userId, "/contact-groups"),
                        HttpMethod.POST,
                        new HttpEntity<>(body, internalHeaders()),
                        note_api.contact.dto.ContactGroupResponse.class)
                .getBody();
    }

    public note_api.contact.dto.ContactGroupResponse updateContactGroup(
            String userId, Long groupId, Map<String, Object> body) {
        return restTemplate
                .exchange(
                        userPath(userId, "/contact-groups/" + groupId),
                        HttpMethod.PUT,
                        new HttpEntity<>(body, internalHeaders()),
                        note_api.contact.dto.ContactGroupResponse.class)
                .getBody();
    }

    public void deleteContactGroup(String userId, Long groupId) {
        restTemplate.exchange(
                userPath(userId, "/contact-groups/" + groupId),
                HttpMethod.DELETE,
                new HttpEntity<>(internalHeaders()),
                Void.class);
    }

    public note_api.contact.dto.ContactGroupResponse replaceContactGroupMembers(
            String userId, Long groupId, Map<String, Object> body) {
        return restTemplate
                .exchange(
                        userPath(userId, "/contact-groups/" + groupId + "/members"),
                        HttpMethod.PUT,
                        new HttpEntity<>(body, internalHeaders()),
                        note_api.contact.dto.ContactGroupResponse.class)
                .getBody();
    }

    public List<note_api.contact.dto.ContactGroupShareResponse> listContactGroupShares(
            String userId, Long groupId) {
        ResponseEntity<List<note_api.contact.dto.ContactGroupShareResponse>> response =
                restTemplate.exchange(
                        userPath(userId, "/contact-groups/" + groupId + "/shares"),
                        HttpMethod.GET,
                        new HttpEntity<>(internalHeaders()),
                        new ParameterizedTypeReference<>() {});

        return response.getBody() == null ? List.of() : response.getBody();
    }

    public note_api.contact.dto.ContactGroupShareResponse shareContactGroup(
            String userId, Long groupId, Map<String, Object> body) {
        return restTemplate
                .exchange(
                        userPath(userId, "/contact-groups/" + groupId + "/shares"),
                        HttpMethod.POST,
                        new HttpEntity<>(body, internalHeaders()),
                        note_api.contact.dto.ContactGroupShareResponse.class)
                .getBody();
    }

    public void revokeContactGroupShare(String userId, Long groupId, Long shareId) {
        restTemplate.exchange(
                userPath(userId, "/contact-groups/" + groupId + "/shares/" + shareId),
                HttpMethod.DELETE,
                new HttpEntity<>(internalHeaders()),
                Void.class);
    }
}
