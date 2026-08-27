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
import note_api.contact.dto.ContactGroupResponse;
import note_api.contact.dto.ContactGroupShareResponse;
import note_api.contact.dto.ContactResponse;
import note_api.contact.dto.RecipientSuggestItem;
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
 * Internal API({@code X-Internal-Api-Key})는 SNS·Gmail 토큰·메일함·주소록에 사용한다.
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
        return restTemplate.getForObject(
                encodedQueryUrl(authServerBaseUrl + "/auth/check-userid", "userId", userId),
                UserIdAvailabilityResponse.class);
    }

    /**
     * SNS 로그인 시작 URL 생성 (브라우저 302 Location).
     * Auth Server {@code GET /auth/social/prepare/{provider}} — PKCE state/code_challenge/redirect_uri 전달.
     * public-url 을 쓰는 이유: 브라우저가 직접 Auth로 가야 하므로 ClusterIP가 아닌 외부 URL.
     */
    public String buildSocialPrepareRedirectUrl(
            String provider, String state, String codeChallenge, String redirectUri) {
        return encodedQueryUrl(
                authServerPublicUrl + "/auth/social/prepare/" + provider,
                "state",
                state,
                "code_challenge",
                codeChallenge,
                "redirect_uri",
                redirectUri);
    }

    /**
     * 로컬 로그인 — {@code POST /auth/login} (JSON).
     * 응답에 access_token + refresh_token 포함.
     */
    public ResponseEntity<TokenResponse> login(LoginRequest request) {
        return restTemplate.postForEntity(
                loginUrl, new HttpEntity<>(request, jsonHeaders()), TokenResponse.class);
    }

    /**
     * 로컬 회원가입 — {@code POST /auth/register} (JSON).
     * Auth Server가 SYS_USER INSERT 후 UserResponse 반환 (토큰 없음).
     */
    public ResponseEntity<UserResponse> register(RegisterRequest request) {
        return restTemplate.postForEntity(
                registerUrl, new HttpEntity<>(request, jsonHeaders()), UserResponse.class);
    }

    /**
     * SNS 계정 SYS_USER 등록 여부 — {@code GET /auth/social/users/status} (internal).
     * JWT {@code sns_provider}/{@code sns_external_id} 로 AUTH_PROVIDER+EXTERNAL_ID 조회.
     */
    public SocialUserStatus getSocialUserStatus(String provider, String externalId) {
        String url = encodedQueryUrl(
                authServerBaseUrl + "/auth/social/users/status",
                "provider",
                provider,
                "externalId",
                externalId);

        ResponseEntity<SocialUserStatusResponse> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(internalHeaders()), SocialUserStatusResponse.class);
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
        Map<String, String> body = Map.of(
                "provider", provider,
                "externalId", externalId,
                "externalEmail", externalEmail != null ? externalEmail : "",
                "userId", userId);

        return restTemplate.exchange(
                authServerBaseUrl + "/auth/social/register",
                HttpMethod.POST,
                new HttpEntity<>(body, internalHeaders()),
                TokenResponse.class);
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

        return postToken(form);
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

        return postToken(form);
    }

    /**
     * Google Gmail API용 access token 조회 — {@code GET /auth/google/access-token} (internal).
     * Auth Server oauth2Login 시 저장해 둔 AuthorizedClient 를 principal 기준으로 조회·갱신.
     * 404 이면 Google/Gmail 미연동 → {@link ApiException}({@link ErrorCode#MAIL_GOOGLE_NOT_LINKED}).
     */
    public String fetchGoogleAccessToken(String principal) {
        String url = encodedQueryUrl(
                authServerBaseUrl + "/auth/google/access-token", "principal", principal);

        try {
            ResponseEntity<Map<String, String>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(internalHeaders()),
                    new ParameterizedTypeReference<>() {});
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
        try {
            ResponseEntity<MailboxCredentialsResponse> response = restTemplate.exchange(
                    userPath(userId, "/mailbox"),
                    HttpMethod.GET,
                    new HttpEntity<>(internalHeaders()),
                    MailboxCredentialsResponse.class);

            MailboxCredentialsResponse body = response.getBody();

            if (body == null || !StringUtils.hasText(body.mailAddress()) || !StringUtils.hasText(body.password())) {
                throw new IllegalStateException("Mailbox credentials missing in auth server response");
            }

            return body;

        } catch (HttpClientErrorException.NotFound ex) {
            throw new ApiException(ErrorCode.MAIL_MAILBOX_NOT_FOUND, userId);
        }
    }

    /** 개인 연락처 목록 — {@code GET /auth/users/{userId}/contacts}. */
    public List<ContactResponse> listContacts(String userId, String q) {
        return getList(userQueryUri(userId, "/contacts", q), new ParameterizedTypeReference<>() {});
    }

    /** 개인 연락처 추가 — {@code POST /auth/users/{userId}/contacts}. */
    public ContactResponse createContact(String userId, Map<String, Object> body) {
        return exchange(userUri(userId, "/contacts"), HttpMethod.POST, body, ContactResponse.class);
    }

    /** 개인 연락처 삭제 — {@code POST /auth/users/{userId}/contacts/{id}/delete}. */
    public void deleteContact(String userId, Long contactId) {
        exchange(userUri(userId, "/contacts/" + contactId + "/delete"), HttpMethod.POST, null, Void.class);
    }

    /** 주소록 자동완성 — {@code GET /auth/users/{userId}/contacts/suggest}. */
    public List<RecipientSuggestItem> suggestContacts(String userId, String q) {
        return getList(userQueryUri(userId, "/contacts/suggest", q), new ParameterizedTypeReference<>() {});
    }

    /** 그룹 목록 — {@code GET /auth/users/{userId}/contact-groups}. */
    public List<ContactGroupResponse> listContactGroups(String userId) {
        return getList(userUri(userId, "/contact-groups"), new ParameterizedTypeReference<>() {});
    }

    /** 그룹 생성 — {@code POST /auth/users/{userId}/contact-groups}. */
    public ContactGroupResponse createContactGroup(String userId, Map<String, Object> body) {
        return exchange(userUri(userId, "/contact-groups"), HttpMethod.POST, body, ContactGroupResponse.class);
    }

    /** 그룹 이름 변경 — {@code POST /auth/users/{userId}/contact-groups/{id}/update}. */
    public ContactGroupResponse updateContactGroup(String userId, Long groupId, Map<String, Object> body) {
        return exchange(
                userUri(userId, "/contact-groups/" + groupId + "/update"),
                HttpMethod.POST,
                body,
                ContactGroupResponse.class);
    }

    /** 그룹 삭제 — {@code POST /auth/users/{userId}/contact-groups/{id}/delete}. */
    public void deleteContactGroup(String userId, Long groupId) {
        exchange(userUri(userId, "/contact-groups/" + groupId + "/delete"), HttpMethod.POST, null, Void.class);
    }

    /** 그룹 멤버 통째 교체 — {@code POST /auth/users/{userId}/contact-groups/{id}/members}. */
    public ContactGroupResponse replaceContactGroupMembers(
            String userId, Long groupId, Map<String, Object> body) {
        return exchange(
                userUri(userId, "/contact-groups/" + groupId + "/members"),
                HttpMethod.POST,
                body,
                ContactGroupResponse.class);
    }

    /** 그룹 공유 목록 — {@code GET /auth/users/{userId}/contact-groups/{id}/shares}. */
    public List<ContactGroupShareResponse> listContactGroupShares(String userId, Long groupId) {
        return getList(
                userUri(userId, "/contact-groups/" + groupId + "/shares"),
                new ParameterizedTypeReference<>() {});
    }

    /** 그룹 공유 — {@code POST /auth/users/{userId}/contact-groups/{id}/shares}. */
    public ContactGroupShareResponse shareContactGroup(
            String userId, Long groupId, Map<String, Object> body) {
        return exchange(
                userUri(userId, "/contact-groups/" + groupId + "/shares"),
                HttpMethod.POST,
                body,
                ContactGroupShareResponse.class);
    }

    /** 그룹 공유 해제 — {@code POST /auth/users/{userId}/contact-groups/{id}/shares/{shareId}/delete}. */
    public void revokeContactGroupShare(String userId, Long groupId, Long shareId) {
        exchange(
                userUri(userId, "/contact-groups/" + groupId + "/shares/" + shareId + "/delete"),
                HttpMethod.POST,
                null,
                Void.class);
    }

    /** Internal API 공통 헤더. */
    private HttpHeaders internalHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Api-Key", internalApiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        return headers;
    }

    /** {@code /auth/users/{userId} + suffix}. */
    private String userPath(String userId, String suffix) {
        return authServerBaseUrl + "/auth/users/" + userId + suffix;
    }

    /** 쿼리 없는 사용자 경로 URI. */
    private URI userUri(String userId, String suffix) {
        return URI.create(userPath(userId, suffix));
    }

    /**
     * 검색어 q를 한 번만 인코딩한 URI.
     * RestTemplate.exchange(String)은 한글을 한 번 더 인코딩한다.
     */
    private URI userQueryUri(String userId, String suffix, String q) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(userPath(userId, suffix));
        if (StringUtils.hasText(q)) {
            builder.queryParam("q", q);
        }

        return builder.build().encode(StandardCharsets.UTF_8).toUri();
    }

    /** GET 목록. 응답이 없으면 빈 리스트. */
    private <T> List<T> getList(URI uri, ParameterizedTypeReference<List<T>> type) {
        List<T> body = restTemplate
                .exchange(uri, HttpMethod.GET, new HttpEntity<>(internalHeaders()), type)
                .getBody();

        return body == null ? List.of() : body;
    }

    /** POST. body가 없으면 헤더만 보낸다. */
    private <T> T exchange(URI uri, HttpMethod method, Object body, Class<T> type) {
        HttpEntity<?> entity = body == null
                ? new HttpEntity<>(internalHeaders())
                : new HttpEntity<>(body, internalHeaders());

        return restTemplate.exchange(uri, method, entity, type).getBody();
    }

    /** JSON POST (login/register). */
    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        return headers;
    }

    /** {@code /oauth2/token} form POST. */
    private ResponseEntity<TokenResponse> postToken(MultiValueMap<String, String> form) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        return restTemplate.postForEntity(tokenUri, new HttpEntity<>(form, headers), TokenResponse.class);
    }

    /** 쿼리 파라미터를 한 번만 인코딩한 URL. */
    private String encodedQueryUrl(String url, String... keyValues) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url);
        for (int i = 0; i < keyValues.length; i += 2) {
            builder.queryParam(keyValues[i], keyValues[i + 1]);
        }

        return builder.build().encode(StandardCharsets.UTF_8).toUriString();
    }
}
