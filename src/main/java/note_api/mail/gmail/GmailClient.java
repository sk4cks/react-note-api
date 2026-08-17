package note_api.mail.gmail;

import note_api.common.exception.ApiException;
import note_api.common.exception.ErrorCode;
import note_api.mail.MailMimeFactory;
import note_api.mail.dto.MailAttachmentContent;
import note_api.mail.dto.MailAttachmentDto;
import note_api.mail.dto.MailFolderDto;
import note_api.mail.dto.MailMessageDetailDto;
import note_api.mail.dto.MailMessageListDto;
import note_api.mail.dto.MailMessageSummaryDto;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class GmailClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final GmailBatchParser batchParser;
    private final GmailMessageParser messageParser;

    public GmailClient(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.batchParser = new GmailBatchParser(objectMapper);
        this.messageParser = new GmailMessageParser();
    }

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

        return messageParser.toDetail(body);
    }

    /**
     * 첨부파일 본문을 받는다. 파일명/타입은 message payload에서, 내용은 attachments API에서 가져온다.
     */
    public MailAttachmentContent getAttachment(String accessToken, String messageId, String attachmentId) {
        MailMessageDetailDto detail = getMessage(accessToken, messageId);
        MailAttachmentDto attachment = detail.attachments().stream()
                .filter(candidate -> candidate.id().equals(attachmentId))
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        ErrorCode.MAIL_ATTACHMENT_NOT_FOUND, "Attachment not found: " + attachmentId));

        String url = GmailApiConstants.USERS_ME_BASE
                + "/messages/" + messageId + "/attachments/" + attachmentId;
        JsonNode body = exchange(accessToken, url, HttpMethod.GET, null);
        byte[] content = Base64.getUrlDecoder().decode(body.path("data").asText(""));

        return new MailAttachmentContent(attachment.filename(), attachment.contentType(), content);
    }

    public void markThreadAsRead(String accessToken, String threadId) {
        String url = GmailApiConstants.USERS_ME_BASE + "/threads/" + threadId + "/modify";
        JsonNode payload = objectMapper.createObjectNode()
                .set("removeLabelIds", objectMapper.createArrayNode().add(GmailApiConstants.LABEL_UNREAD));
        exchange(accessToken, url, HttpMethod.POST, payload);
    }

    public void sendMessage(String accessToken, SendMailRequest request) {
        byte[] mimeBytes = MailMimeFactory.toRfc822Bytes(null, request);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(mimeBytes);

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
        String responseBoundary = batchParser.extractBatchBoundary(contentType != null ? contentType.toString() : "");
        List<JsonNode> bodies = batchParser.parseBatchResponse(responseBody, responseBoundary);

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
        String responseBoundary = batchParser.extractBatchBoundary(contentType != null ? contentType.toString() : "");
        List<JsonNode> threadBodies = batchParser.parseBatchResponse(responseBody, responseBoundary);

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
                MailMessageSummaryDto summary = messageParser.toThreadSummary(folder, body);
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

}
