package note_api.mail.gmail;

import note_api.common.exception.ApiException;
import note_api.common.exception.ErrorCode;
import note_api.common.util.KoreanTextMatcher;
import note_api.mail.MailMimeFactory;
import note_api.mail.dto.MailAttachmentContent;
import note_api.mail.dto.MailAttachmentDto;
import note_api.mail.dto.MailFolderDto;
import note_api.mail.dto.MailMessageDetailDto;
import note_api.mail.dto.MailMessageListDto;
import note_api.mail.dto.MailMessageSummaryDto;
import note_api.mail.dto.MailRecipientSuggestion;
import note_api.mail.dto.SaveDraftRequest;
import note_api.mail.dto.SendMailRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;

@Component
public class GmailClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final GmailBatchParser batchParser;
    private final GmailMessageParser messageParser;

    /** REST 호출과 JSON 파서, batch·메시지 파서 준비. */
    public GmailClient(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.batchParser = new GmailBatchParser(objectMapper);
        this.messageParser = new GmailMessageParser();
    }

    /** 폴더에 맞는 Gmail 목록 조회. 받은편지함은 Primary 쿼리, 나머지는 라벨. */
    public MailMessageListDto listMessages(
            String accessToken, String folder, int maxResults, String pageToken) {
        // 받은편지함은 Primary만. 프로모션·소셜 탭은 빼 둔 목록.
        if (GmailApiConstants.FOLDER_INBOX.equals(folder)) {
            return listThreads(
                    accessToken,
                    folder,
                    maxResults,
                    pageToken,
                    GmailApiConstants.QUERY_INBOX_PRIMARY,
                    null);
        }

        return listThreads(
                accessToken, folder, maxResults, pageToken, null, toListLabelId(folder));
    }

    /** threads.list 후 id만 오면 batch로 요약 조회. */
    private MailMessageListDto listThreads(
            String accessToken,
            String folder,
            int maxResults,
            String pageToken,
            String query,
            String labelId) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(
                        GmailApiConstants.USERS_ME_BASE + "/threads")
                .queryParam("maxResults", maxResults);

        if (StringUtils.hasText(query)) {
            builder.queryParam("q", query);
        }

        if (StringUtils.hasText(labelId)) {
            builder.queryParam("labelIds", labelId);
        }

        if (StringUtils.hasText(pageToken)) {
            builder.queryParam("pageToken", pageToken);
        }

        String listUrl = builder.build().toUriString();

        JsonNode listBody = exchange(accessToken, listUrl, HttpMethod.GET, null);
        JsonNode threads = listBody.path("threads");
        String nextPageToken = listBody.path("nextPageToken").asText(null);

        if (!StringUtils.hasText(nextPageToken)) {
            nextPageToken = null;
        }

        if (!threads.isArray() || threads.isEmpty()) {
            return new MailMessageListDto(List.of(), nextPageToken);
        }

        // 목록 API는 id만 줌. 제목·미리보기는 batch로 한 번에 받는 조회.
        List<String> threadIds = new ArrayList<>();

        for (JsonNode threadRef : threads) {
            String id = threadRef.path("id").asText(null);

            if (StringUtils.hasText(id)) {
                threadIds.add(id);
            }
        }

        if (threadIds.isEmpty()) {
            return new MailMessageListDto(List.of(), nextPageToken);
        }

        List<MailMessageSummaryDto> messages = fetchThreadSummariesBatch(accessToken, threadIds, folder);

        return new MailMessageListDto(messages, nextPageToken);
    }

    /** format=full 메시지 조회 후 상세 DTO 변환. */
    public MailMessageDetailDto getMessage(String accessToken, String messageId) {
        // format=full 메시지 후 상세 DTO.
        JsonNode body = exchange(accessToken, messageUrl(messageId), HttpMethod.GET, null);

        return messageParser.toDetail(body);
    }

    /**
     * 첨부파일 본문 수신. 파일명/타입은 message payload, 내용은 attachments API.
     */
    public MailAttachmentContent getAttachment(String accessToken, String messageId, String attachmentId) {
        MailAttachmentDto attachment =
                messageParser.findAttachment(exchange(accessToken, messageUrl(messageId), HttpMethod.GET, null), attachmentId);

        if (attachment == null) {
            throw new ApiException(ErrorCode.MAIL_ATTACHMENT_NOT_FOUND, attachmentId);
        }

        // 파일명·타입은 payload, 실제 바이트는 attachments API.
        String url = GmailApiConstants.USERS_ME_BASE
                + "/messages/" + messageId + "/attachments/" + attachmentId;
        JsonNode body = exchange(accessToken, url, HttpMethod.GET, null);
        byte[] content = Base64.getUrlDecoder().decode(body.path("data").asText(""));

        return new MailAttachmentContent(attachment.filename(), attachment.contentType(), content);
    }

    /** 스레드에서 UNREAD 라벨 제거. */
    public void markThreadAsRead(String accessToken, String threadId) {
        // UNREAD 라벨 제거.
        String url = GmailApiConstants.USERS_ME_BASE + "/threads/" + threadId + "/modify";
        JsonNode payload = objectMapper.createObjectNode()
                .set("removeLabelIds", objectMapper.createArrayNode().add(GmailApiConstants.LABEL_UNREAD));
        exchange(accessToken, url, HttpMethod.POST, payload);
    }

    /** RFC822 MIME을 base64url raw로 보내는 발송. */
    public void sendMessage(String accessToken, SendMailRequest request) {
        // Gmail send는 RFC822 MIME을 base64url raw로 받는 발송.
        byte[] mimeBytes = MailMimeFactory.toRfc822Bytes(null, request);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(mimeBytes);

        String url = GmailApiConstants.USERS_ME_BASE + "/messages/send";
        JsonNode payload = objectMapper.createObjectNode().put("raw", encoded);
        exchange(accessToken, url, HttpMethod.POST, payload);
    }

    /** Gmail drafts.create/update. 목록과 맞추려고 message id를 돌려주는 저장. */
    public String saveDraft(String accessToken, SaveDraftRequest request) {
        byte[] mimeBytes = MailMimeFactory.toRfc822Bytes(null, request.toSendRequest());
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(mimeBytes);
        JsonNode message = objectMapper.createObjectNode().put("raw", encoded);

        if (StringUtils.hasText(request.id())) {
            // 기존 message id에 대응하는 draft가 있으면 그 draft 갱신.
            String draftId = findDraftId(accessToken, request.id());

            if (StringUtils.hasText(draftId)) {
                JsonNode payload = objectMapper.createObjectNode()
                        .put("id", draftId)
                        .set("message", message);
                JsonNode body = exchange(
                        accessToken,
                        GmailApiConstants.USERS_ME_BASE + "/drafts/" + draftId,
                        HttpMethod.PUT,
                        payload);
                String messageId = body.path("message").path("id").asText(null);

                return StringUtils.hasText(messageId) ? messageId : request.id();
            }
        }

        JsonNode payload = objectMapper.createObjectNode().set("message", message);
        // 새 초안 생성.
        JsonNode body = exchange(accessToken, GmailApiConstants.USERS_ME_BASE + "/drafts", HttpMethod.POST, payload);
        String messageId = body.path("message").path("id").asText(null);

        if (!StringUtils.hasText(messageId)) {
            throw new IllegalStateException("Gmail draft create returned no message id");
        }

        return messageId;
    }

    /** message id에 대응하는 draft 삭제. 없으면 생략. */
    public void deleteDraftByMessageId(String accessToken, String messageId) {
        String draftId = findDraftId(accessToken, messageId);

        if (!StringUtils.hasText(draftId)) {
            return;
        }

        exchange(accessToken, GmailApiConstants.USERS_ME_BASE + "/drafts/" + draftId, HttpMethod.DELETE, null);
    }

    /** 메시지를 휴지통으로 옮기는 trash. */
    public void trashMessage(String accessToken, String messageId) {
        exchange(
                accessToken,
                GmailApiConstants.USERS_ME_BASE + "/messages/" + messageId + "/trash",
                HttpMethod.POST,
                null);
    }

    /** 휴지통 메시지를 완전히 지우는 삭제. */
    public void deleteMessage(String accessToken, String messageId) {
        exchange(
                accessToken,
                GmailApiConstants.USERS_ME_BASE + "/messages/" + messageId,
                HttpMethod.DELETE,
                null);
    }

    /** 휴지통에서 꺼내는 복원. 받은·보낸·임시 라벨이 없으면 받은편지함. */
    public void restoreMessage(String accessToken, String messageId) {
        JsonNode message = exchange(
                accessToken,
                GmailApiConstants.USERS_ME_BASE + "/messages/" + messageId + "/untrash",
                HttpMethod.POST,
                null);

        // 받은·보낸·임시 라벨이 있으면 그대로 둠.
        if (hasLabel(message, GmailApiConstants.LABEL_INBOX)
                || hasLabel(message, GmailApiConstants.LABEL_SENT)
                || hasLabel(message, GmailApiConstants.LABEL_DRAFT)) {
            return;
        }

        // 갈 곳이 없으면 INBOX 라벨 추가.
        JsonNode payload = objectMapper.createObjectNode()
                .set("addLabelIds", objectMapper.createArrayNode().add(GmailApiConstants.LABEL_INBOX));
        exchange(
                accessToken,
                GmailApiConstants.USERS_ME_BASE + "/messages/" + messageId + "/modify",
                HttpMethod.POST,
                payload);
    }

    /** labelIds에 해당 라벨이 있는지 확인. */
    private static boolean hasLabel(JsonNode message, String label) {
        JsonNode labels = message.path("labelIds");

        if (!labels.isArray()) {
            return false;
        }

        for (JsonNode node : labels) {
            if (label.equals(node.asText())) {
                return true;
            }
        }

        return false;
    }

    /** drafts 목록을 넘겨 message id에 해당하는 draft id 조회. */
    private String findDraftId(String accessToken, String messageId) {
        String pageToken = null;

        do {
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(
                            GmailApiConstants.USERS_ME_BASE + "/drafts")
                    .queryParam("maxResults", 100);

            if (StringUtils.hasText(pageToken)) {
                builder.queryParam("pageToken", pageToken);
            }

            JsonNode body = exchange(accessToken, builder.build().toUriString(), HttpMethod.GET, null);

            for (JsonNode draft : body.path("drafts")) {
                if (messageId.equals(draft.path("message").path("id").asText())) {
                    return draft.path("id").asText(null);
                }
            }

            pageToken = body.path("nextPageToken").asText(null);

            if (!StringUtils.hasText(pageToken)) {
                break;
            }
        } while (true);

        return null;
    }

    /** 뱃지용 폴더 건수. 받은편지함은 Primary unread, 나머지는 라벨 통계. */
    public List<MailFolderDto> getFolderStats(String accessToken) {
        List<String> labelIds = List.of(GmailApiConstants.LABEL_DRAFT, GmailApiConstants.LABEL_TRASH);
        Map<String, JsonNode> labelsById = fetchLabelsBatch(accessToken, labelIds);

        // 받은편지함 뱃지는 Primary unread 스레드 수.
        int inboxUnread =
                countThreadsByQuery(accessToken, GmailApiConstants.QUERY_INBOX_PRIMARY_UNREAD);

        return List.of(
                new MailFolderDto(GmailApiConstants.FOLDER_INBOX, "받은편지함", inboxUnread),
                toFolderDto(
                        GmailApiConstants.FOLDER_SENT,
                        "보낸편지함",
                        null,
                        GmailApiConstants.LABEL_FIELD_THREADS_TOTAL),
                toFolderDto(
                        GmailApiConstants.FOLDER_DRAFT,
                        "임시보관함",
                        labelsById.get(GmailApiConstants.LABEL_DRAFT),
                        GmailApiConstants.LABEL_FIELD_THREADS_TOTAL),
                toFolderDto(
                        GmailApiConstants.FOLDER_TRASH,
                        "휴지통",
                        labelsById.get(GmailApiConstants.LABEL_TRASH),
                        GmailApiConstants.LABEL_FIELD_THREADS_TOTAL));
    }

    /**
     * 최근 inbox/sent 메시지 헤더에서 From/To/Cc를 모아 수신자 후보를 만드는 조회.
     */
    public List<MailRecipientSuggestion> suggestRecipients(String accessToken, String query) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(
                        GmailApiConstants.USERS_ME_BASE + "/messages")
                .queryParam("maxResults", 40)
                .queryParam("q", "in:inbox OR in:sent");
        JsonNode listBody = exchange(accessToken, builder.build().toUriString(), HttpMethod.GET, null);
        JsonNode messages = listBody.path("messages");

        if (!messages.isArray() || messages.isEmpty()) {
            return List.of();
        }

        // From/To/Cc만 metadata로 받음. 본문은 불필요.
        List<String> paths = new ArrayList<>();

        for (JsonNode message : messages) {
            String id = message.path("id").asText(null);

            if (StringUtils.hasText(id)) {
                paths.add("/gmail/v1/users/me/messages/"
                        + id
                        + "?format=metadata&metadataHeaders=From&metadataHeaders=To&metadataHeaders=Cc");
            }
        }

        List<JsonNode> bodies = postBatch(accessToken, paths);
        LinkedHashMap<String, MailRecipientSuggestion> byEmail = new LinkedHashMap<>();
        String q = query == null ? "" : query.trim().toLowerCase();

        for (JsonNode body : bodies) {
            collectHeaderAddresses(body.path("payload").path("headers"), byEmail, q);
        }

        return byEmail.values().stream().limit(20).toList();
    }

    /** From/To/Cc 헤더를 주소별로 나눠 수신자 후보에 넣는 수집. */
    private static void collectHeaderAddresses(
            JsonNode headers, LinkedHashMap<String, MailRecipientSuggestion> byEmail, String query) {
        if (!headers.isArray()) {
            return;
        }
        for (JsonNode header : headers) {
            String name = header.path("name").asText("");
            if (!"from".equalsIgnoreCase(name)
                    && !"to".equalsIgnoreCase(name)
                    && !"cc".equalsIgnoreCase(name)) {
                continue;
            }
            String value = header.path("value").asText("");
            if (!StringUtils.hasText(value)) {
                continue;
            }
            for (String part : value.split(",")) {
                ParsedFrom parsed = parseAddress(part.trim());
                if (!StringUtils.hasText(parsed.email())) {
                    continue;
                }
                String key = parsed.email().toLowerCase();
                if (!KoreanTextMatcher.matches(query, parsed.displayName(), parsed.email())) {
                    continue;
                }
                byEmail.putIfAbsent(key, MailRecipientSuggestion.of(parsed.email(), parsed.displayName()));
            }
        }
    }

    /** "이름 <email>" 또는 이메일만 있는 헤더 분리. */
    private static ParsedFrom parseAddress(String raw) {
        Matcher matcher = GmailApiConstants.FROM_HEADER.matcher(raw);
        if (matcher.matches()) {
            if (matcher.group(2) != null) {
                String name = matcher.group(1) != null ? matcher.group(1).trim() : matcher.group(2);

                return new ParsedFrom(name, matcher.group(2));
            }

            return new ParsedFrom(matcher.group(3), matcher.group(3));
        }
        if (raw.contains("@")) {
            return new ParsedFrom(raw, raw);
        }

        return new ParsedFrom("", "");
    }

    private record ParsedFrom(String displayName, String email) {}

    /** 쿼리에 맞는 스레드를 페이지마다 더하는 건수 조회. */
    private int countThreadsByQuery(String accessToken, String query) {
        int count = 0;
        String pageToken = null;
        do {
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(
                            GmailApiConstants.USERS_ME_BASE + "/threads")
                    .queryParam("q", query)
                    .queryParam("maxResults", GmailApiConstants.THREAD_COUNT_PAGE_SIZE);
            if (StringUtils.hasText(pageToken)) {
                builder.queryParam("pageToken", pageToken);
            }
            JsonNode body = exchange(accessToken, builder.build().toUriString(), HttpMethod.GET, null);
            JsonNode threads = body.path("threads");
            if (threads.isArray()) {
                count += threads.size();
            }
            pageToken = body.path("nextPageToken").asText(null);
            if (!StringUtils.hasText(pageToken)) {
                break;
            }
        } while (true);

        return count;
    }

    /** 라벨 id 여러 개를 batch로 조회. */
    private Map<String, JsonNode> fetchLabelsBatch(String accessToken, List<String> labelIds) {
        List<String> paths = new ArrayList<>(labelIds.size());
        for (String labelId : labelIds) {
            paths.add("/gmail/v1/users/me/labels/" + labelId);
        }

        return indexById(postBatch(accessToken, paths));
    }

    /** 라벨 JSON의 count 필드를 폴더 DTO로 변환. */
    private static MailFolderDto toFolderDto(
            String folderId, String label, JsonNode body, String countField) {
        int count = body != null ? body.path(countField).asInt(0) : 0;

        return new MailFolderDto(folderId, label, count);
    }

    /** 스레드 id 목록을 batch로 받아 목록 카드 변환. */
    private List<MailMessageSummaryDto> fetchThreadSummariesBatch(
            String accessToken, List<String> threadIds, String folder) {
        List<String> paths = new ArrayList<>(threadIds.size());
        for (String threadId : threadIds) {
            paths.add("/gmail/v1/users/me/threads/" + threadId + "?" + GmailApiConstants.METADATA_QUERY);
        }
        Map<String, JsonNode> byThreadId = indexById(postBatch(accessToken, paths));

        List<MailMessageSummaryDto> result = new ArrayList<>(threadIds.size());
        for (String threadId : threadIds) {
            JsonNode body = byThreadId.get(threadId);
            if (body != null) {
                MailMessageSummaryDto summary = messageParser.toThreadSummary(folder, body);
                if (summary != null) {
                    result.add(summary);
                }
            }
        }

        return result;
    }

    /** Gmail batch API로 GET 경로들을 한 번에 보내고, 각 JSON body를 반환. */
    private List<JsonNode> postBatch(String accessToken, List<String> relativeGets) {
        String boundary = "batch_gmail_" + UUID.randomUUID();
        StringBuilder sb = new StringBuilder();

        // multipart/mixed 안에 HTTP GET을 하나씩 넣는 요청 조립.
        for (String path : relativeGets) {
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Type: application/http\r\n");
            sb.append("\r\n");
            sb.append("GET ").append(path).append("\r\n\r\n");
        }

        sb.append("--").append(boundary).append("--\r\n");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.parseMediaType("multipart/mixed; boundary=" + boundary));
        HttpEntity<String> entity = new HttpEntity<>(sb.toString(), headers);

        ResponseEntity<byte[]> response = restTemplate.exchange(
                GmailApiConstants.BATCH_URL, HttpMethod.POST, entity, byte[].class);
        String responseBody = response.getBody() == null
                ? ""
                : new String(response.getBody(), StandardCharsets.UTF_8);
        MediaType contentType = response.getHeaders().getContentType();
        String responseBoundary = batchParser.extractBatchBoundary(contentType != null ? contentType.toString() : "");

        return batchParser.parseBatchResponse(responseBody, responseBoundary);
    }

    /** batch JSON을 id 키로 묶는 색인. */
    private static Map<String, JsonNode> indexById(List<JsonNode> bodies) {
        Map<String, JsonNode> byId = new HashMap<>();
        for (JsonNode body : bodies) {
            String id = body.path("id").asText(null);
            if (StringUtils.hasText(id)) {
                byId.put(id, body);
            }
        }

        return byId;
    }

    /** Bearer 토큰으로 Gmail REST 호출 후 JSON 파싱. */
    private JsonNode exchange(String accessToken, String url, HttpMethod method, JsonNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }

        HttpEntity<String> entity;

        try {
            entity = new HttpEntity<>(body == null ? null : objectMapper.writeValueAsString(body), headers);

        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize Gmail request", ex);
        }

        ResponseEntity<String> response = restTemplate.exchange(url, method, entity, String.class);
        String responseBody = response.getBody();

        if (!StringUtils.hasText(responseBody)) {
            return objectMapper.nullNode();
        }

        try {
            return objectMapper.readTree(responseBody);

        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse Gmail response", ex);
        }
    }

    /** SPA 폴더 id를 Gmail 목록용 라벨 id로 변환. */
    private static String toListLabelId(String folder) {
        switch (folder) {
            case GmailApiConstants.FOLDER_SENT:
                return GmailApiConstants.LABEL_SENT;
            case GmailApiConstants.FOLDER_DRAFT:
                return GmailApiConstants.LABEL_DRAFT;
            case GmailApiConstants.FOLDER_TRASH:
                return GmailApiConstants.LABEL_TRASH;
            default:
                return GmailApiConstants.LABEL_INBOX;
        }
    }

    /** format=full 메시지 URL. */
    private static String messageUrl(String messageId) {
        return UriComponentsBuilder.fromUriString(GmailApiConstants.USERS_ME_BASE + "/messages/" + messageId)
                .queryParam("format", "full")
                .build()
                .toUriString();
    }

}
