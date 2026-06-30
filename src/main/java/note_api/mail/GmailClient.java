package note_api.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class GmailClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public MailMessageListDto listMessages(
            String accessToken, String folder, int maxResults, String pageToken) {
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

    public MailMessageDetailDto getMessage(String accessToken, String messageId) {
        String url = UriComponentsBuilder.fromUriString(GmailApiConstants.USERS_ME_BASE + "/messages/" + messageId)
                .queryParam("format", "full")
                .build()
                .toUriString();

        JsonNode body = exchange(accessToken, url, HttpMethod.GET, null);
        return toDetail(body);
    }

    public void markThreadAsRead(String accessToken, String threadId) {
        String url = GmailApiConstants.USERS_ME_BASE + "/threads/" + threadId + "/modify";
        JsonNode payload = objectMapper.createObjectNode()
                .set("removeLabelIds", objectMapper.createArrayNode().add(GmailApiConstants.LABEL_UNREAD));
        exchange(accessToken, url, HttpMethod.POST, payload);
    }

    public void sendMessage(String accessToken, String to, String subject, String textBody) {
        String raw = buildRawMime(to, subject, textBody);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));

        String url = GmailApiConstants.USERS_ME_BASE + "/messages/send";
        JsonNode payload = objectMapper.createObjectNode().put("raw", encoded);
        exchange(accessToken, url, HttpMethod.POST, payload);
    }

    public List<MailFolderDto> getFolderStats(String accessToken) {
        List<String> labelIds = List.of(GmailApiConstants.LABEL_DRAFT);
        Map<String, JsonNode> labelsById = fetchLabelsBatch(accessToken, labelIds);

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
                        GmailApiConstants.LABEL_FIELD_THREADS_TOTAL));
    }

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

    private Map<String, JsonNode> fetchLabelsBatch(String accessToken, List<String> labelIds) {
        String boundary = "batch_gmail_" + UUID.randomUUID();
        StringBuilder sb = new StringBuilder();
        for (String labelId : labelIds) {
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Type: application/http\r\n");
            sb.append("\r\n");
            sb.append("GET /gmail/v1/users/me/labels/")
                    .append(labelId)
                    .append("\r\n\r\n");
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
        String responseBoundary = extractBatchBoundary(contentType != null ? contentType.toString() : "");
        List<JsonNode> bodies = parseBatchResponse(responseBody, responseBoundary);

        Map<String, JsonNode> labelsById = new HashMap<>();
        for (JsonNode body : bodies) {
            String id = body.path("id").asText(null);
            if (StringUtils.hasText(id)) {
                labelsById.put(id, body);
            }
        }
        return labelsById;
    }

    private static MailFolderDto toFolderDto(
            String folderId, String label, JsonNode body, String countField) {
        int count = body != null ? body.path(countField).asInt(0) : 0;
        return new MailFolderDto(folderId, label, count);
    }

    private List<MailMessageSummaryDto> fetchThreadSummariesBatch(
            String accessToken, List<String> threadIds, String folder) {
        String boundary = "batch_gmail_" + UUID.randomUUID();
        String requestBody = buildThreadBatchRequestBody(boundary, threadIds);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.parseMediaType("multipart/mixed; boundary=" + boundary));
        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<byte[]> response = restTemplate.exchange(
                GmailApiConstants.BATCH_URL, HttpMethod.POST, entity, byte[].class);

        String responseBody = response.getBody() == null
                ? ""
                : new String(response.getBody(), StandardCharsets.UTF_8);
        MediaType contentType = response.getHeaders().getContentType();
        String responseBoundary = extractBatchBoundary(contentType != null ? contentType.toString() : "");
        List<JsonNode> threadBodies = parseBatchResponse(responseBody, responseBoundary);

        Map<String, JsonNode> byThreadId = new HashMap<>();
        for (JsonNode body : threadBodies) {
            String id = body.path("id").asText(null);
            if (StringUtils.hasText(id)) {
                byThreadId.put(id, body);
            }
        }

        List<MailMessageSummaryDto> result = new ArrayList<>(threadIds.size());
        for (String threadId : threadIds) {
            JsonNode body = byThreadId.get(threadId);
            if (body != null) {
                MailMessageSummaryDto summary = toThreadSummary(folder, body);
                if (summary != null) {
                    result.add(summary);
                }
            }
        }
        return result;
    }

    private String buildThreadBatchRequestBody(String boundary, List<String> threadIds) {
        StringBuilder sb = new StringBuilder();
        for (String threadId : threadIds) {
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Type: application/http\r\n");
            sb.append("\r\n");
            sb.append("GET /gmail/v1/users/me/threads/")
                    .append(threadId)
                    .append("?")
                    .append(GmailApiConstants.METADATA_QUERY)
                    .append("\r\n\r\n");
        }
        sb.append("--").append(boundary).append("--\r\n");
        return sb.toString();
    }

    private List<JsonNode> parseBatchResponse(String responseBody, String boundary) {
        if (!StringUtils.hasText(responseBody)) {
            return List.of();
        }

        List<JsonNode> result = new ArrayList<>();
        if (StringUtils.hasText(boundary)) {
            String[] parts = responseBody.split("--" + Pattern.quote(boundary));
            for (String part : parts) {
                parseJsonFromBatchPart(part).ifPresent(json -> {
                    try {
                        result.add(objectMapper.readTree(json));
                    } catch (Exception ex) {
                        throw new IllegalStateException("Failed to parse Gmail batch part", ex);
                    }
                });
            }
            if (!result.isEmpty()) {
                return result;
            }
        }

        return parseBatchJsonFallback(responseBody);
    }

    private List<JsonNode> parseBatchJsonFallback(String responseBody) {
        List<JsonNode> result = new ArrayList<>();
        int idx = 0;
        while (idx < responseBody.length()) {
            int start = responseBody.indexOf('{', idx);
            if (start < 0) {
                break;
            }
            int end = findJsonObjectEnd(responseBody, start);
            if (end < 0) {
                break;
            }
            try {
                result.add(objectMapper.readTree(responseBody.substring(start, end + 1)));
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to parse Gmail batch JSON", ex);
            }
            idx = end + 1;
        }
        return result;
    }

    private java.util.Optional<String> parseJsonFromBatchPart(String part) {
        int jsonStart = part.indexOf('{');
        if (jsonStart < 0) {
            return java.util.Optional.empty();
        }
        int jsonEnd = findJsonObjectEnd(part, jsonStart);
        if (jsonEnd < 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(part.substring(jsonStart, jsonEnd + 1));
    }

    private static int findJsonObjectEnd(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String extractBatchBoundary(String contentType) {
        Matcher matcher = GmailApiConstants.BATCH_BOUNDARY.matcher(contentType);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private MailMessageSummaryDto toThreadSummary(String folder, JsonNode thread) {
        JsonNode latestMessage = findLatestMessage(thread.path("messages"));
        if (latestMessage == null) {
            return null;
        }

        String messageId = latestMessage.path("id").asText();
        HeaderValues headers = extractHeaders(latestMessage.path("payload"));
        ParsedFrom parsedFrom = parseFrom(headers.from());
        boolean unread = hasUnreadMessage(thread.path("messages"));
        String preview = thread.path("snippet").asText("");
        if (!StringUtils.hasText(preview)) {
            preview = latestMessage.path("snippet").asText("");
        }

        return new MailMessageSummaryDto(
                messageId,
                folder,
                parsedFrom.displayName(),
                parsedFrom.email(),
                headers.subject(),
                preview,
                formatDate(latestMessage.path("internalDate").asText(null), headers.date()),
                unread);
    }

    private static JsonNode findLatestMessage(JsonNode messages) {
        if (!messages.isArray() || messages.isEmpty()) {
            return null;
        }

        JsonNode latest = messages.get(0);
        long latestDate = latest.path("internalDate").asLong(0);
        for (JsonNode message : messages) {
            long date = message.path("internalDate").asLong(0);
            if (date >= latestDate) {
                latestDate = date;
                latest = message;
            }
        }
        return latest;
    }

    private static boolean hasUnreadMessage(JsonNode messages) {
        if (!messages.isArray()) {
            return false;
        }
        for (JsonNode message : messages) {
            if (hasLabel(message, GmailApiConstants.LABEL_UNREAD)) {
                return true;
            }
        }
        return false;
    }

    private MailMessageDetailDto toDetail(JsonNode body) {
        String id = body.path("id").asText();
        HeaderValues headers = extractHeaders(body.path("payload"));
        ParsedFrom parsedFrom = parseFrom(headers.from());
        String folder = resolveFolder(body);
        boolean unread = hasLabel(body, GmailApiConstants.LABEL_UNREAD);
        BodyContent bodyContent = extractBody(body.path("payload"));

        return new MailMessageDetailDto(
                id,
                body.path("threadId").asText(),
                folder,
                parsedFrom.displayName(),
                parsedFrom.email(),
                headers.to(),
                headers.subject(),
                body.path("snippet").asText(""),
                bodyContent.body(),
                bodyContent.contentType(),
                formatDate(body.path("internalDate").asText(null), headers.date()),
                unread);
    }

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

    private static String toListLabelId(String folder) {
        return switch (folder) {
            case GmailApiConstants.FOLDER_SENT -> GmailApiConstants.LABEL_SENT;
            case GmailApiConstants.FOLDER_DRAFT -> GmailApiConstants.LABEL_DRAFT;
            default -> GmailApiConstants.LABEL_INBOX;
        };
    }

    private static String resolveFolder(JsonNode body) {
        if (hasLabel(body, GmailApiConstants.LABEL_DRAFT)) {
            return GmailApiConstants.FOLDER_DRAFT;
        }
        if (hasLabel(body, GmailApiConstants.LABEL_INBOX)) {
            return GmailApiConstants.FOLDER_INBOX;
        }
        if (hasLabel(body, GmailApiConstants.LABEL_SENT)) {
            return GmailApiConstants.FOLDER_SENT;
        }
        return GmailApiConstants.FOLDER_INBOX;
    }

    private static boolean hasLabel(JsonNode body, String label) {
        JsonNode labels = body.path("labelIds");
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

    private static HeaderValues extractHeaders(JsonNode payload) {
        String from = "";
        String to = "";
        String subject = "";
        String date = "";
        JsonNode headers = payload.path("headers");
        if (headers.isArray()) {
            for (JsonNode header : headers) {
                String name = header.path("name").asText("");
                String value = header.path("value").asText("");
                switch (name.toLowerCase()) {
                    case GmailApiConstants.HEADER_FROM -> from = value;
                    case GmailApiConstants.HEADER_TO -> to = value;
                    case GmailApiConstants.HEADER_SUBJECT -> subject = value;
                    case GmailApiConstants.HEADER_DATE -> date = value;
                    default -> { }
                }
            }
        }
        return new HeaderValues(from, to, subject, date);
    }

    private static BodyContent extractBody(JsonNode payload) {
        String html = findBodyByMimeType(payload, GmailApiConstants.MIME_TEXT_HTML);
        if (StringUtils.hasText(html)) {
            return new BodyContent(html, GmailApiConstants.MIME_TEXT_HTML);
        }
        String plain = findBodyByMimeType(payload, GmailApiConstants.MIME_TEXT_PLAIN);
        if (StringUtils.hasText(plain)) {
            return new BodyContent(plain, GmailApiConstants.MIME_TEXT_PLAIN);
        }
        return new BodyContent("", GmailApiConstants.MIME_TEXT_PLAIN);
    }

    private static String findBodyByMimeType(JsonNode payload, String mimeType) {
        String mime = payload.path("mimeType").asText("");
        String bodyData = payload.path("body").path("data").asText(null);
        if (StringUtils.hasText(bodyData) && mimeType.equalsIgnoreCase(mime)) {
            return decodeBase64Url(bodyData);
        }

        JsonNode parts = payload.path("parts");
        if (!parts.isArray()) {
            return "";
        }
        for (JsonNode part : parts) {
            String found = findBodyByMimeType(part, mimeType);
            if (StringUtils.hasText(found)) {
                return found;
            }
        }
        return "";
    }

    private static String decodeBase64Url(String data) {
        byte[] decoded = Base64.getUrlDecoder().decode(data);
        return new String(decoded, StandardCharsets.UTF_8);
    }

    private static ParsedFrom parseFrom(String fromHeader) {
        if (!StringUtils.hasText(fromHeader)) {
            return new ParsedFrom("Unknown", "");
        }
        Matcher matcher = GmailApiConstants.FROM_HEADER.matcher(fromHeader.trim());
        if (matcher.matches()) {
            if (matcher.group(2) != null) {
                String name = matcher.group(1) != null ? matcher.group(1).trim() : matcher.group(2);
                return new ParsedFrom(name, matcher.group(2));
            }
            return new ParsedFrom(matcher.group(3), matcher.group(3));
        }
        return new ParsedFrom(fromHeader, fromHeader);
    }

    private static String formatDate(String internalDate, String headerDate) {
        if (StringUtils.hasText(internalDate)) {
            try {
                return Instant.ofEpochMilli(Long.parseLong(internalDate)).toString();
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return StringUtils.hasText(headerDate) ? headerDate : Instant.now().toString();
    }

    private static String buildRawMime(String to, String subject, String body) {
        return "MIME-Version: 1.0\r\n"
                + "To: " + to + "\r\n"
                + "Subject: " + encodeMimeHeader(subject) + "\r\n"
                + "Content-Type: " + GmailApiConstants.MIME_TEXT_PLAIN + "; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: base64\r\n"
                + "\r\n"
                + Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
    }

    private static String encodeMimeHeader(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        if (value.chars().allMatch(ch -> ch < 128)) {
            return value;
        }
        return "=?UTF-8?B?"
                + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))
                + "?=";
    }

    private record HeaderValues(String from, String to, String subject, String date) {}

    private record ParsedFrom(String displayName, String email) {}

    private record BodyContent(String body, String contentType) {}
}
