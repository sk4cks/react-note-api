package note_api.mail.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import note_api.mail.dto.MailAttachmentDto;
import note_api.mail.dto.MailMessageDetailDto;
import note_api.mail.dto.MailMessageSummaryDto;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Gmail message/thread JSON을 react-note 메일 DTO로 변환.
 */
final class GmailMessageParser {

    /** thread 목록은 최신 message 하나를 대표 카드로 내리는 요약. */
    MailMessageSummaryDto toThreadSummary(String folder, JsonNode thread) {
        // 스레드에서 가장 최근 메시지 한 통.
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
                headers.to(),
                headers.subject(),
                preview,
                formatDate(latestMessage.path("internalDate").asText(null), headers.date()),
                unread);
    }

    /** message 상세는 folder / sender / body / unread를 한 번에 계산해 detail DTO로 만드는 변환. */
    MailMessageDetailDto toDetail(JsonNode body) {
        // 헤더·폴더·본문·첨부를 한 DTO로 모으는 변환.
        String id = body.path("id").asText();
        HeaderValues headers = extractHeaders(body.path("payload"));
        ParsedFrom parsedFrom = parseFrom(headers.from());
        String folder = resolveFolder(body);
        boolean unread = hasLabel(body, GmailApiConstants.LABEL_UNREAD);
        BodyContent bodyContent = extractBody(body.path("payload"));
        List<MailAttachmentDto> attachments = new ArrayList<>();
        collectAttachments(body.path("payload"), attachments);

        return new MailMessageDetailDto(
                id,
                body.path("threadId").asText(),
                folder,
                parsedFrom.displayName(),
                parsedFrom.email(),
                headers.to(),
                headers.cc(),
                headers.bcc(),
                headers.subject(),
                body.path("snippet").asText(""),
                bodyContent.body(),
                bodyContent.contentType(),
                formatDate(body.path("internalDate").asText(null), headers.date()),
                unread,
                attachments);
    }

    /** message payload에서 attachmentId가 일치하는 첨부 탐색. 없으면 null. */
    MailAttachmentDto findAttachment(JsonNode body, String attachmentId) {
        // payload의 첨부 목록에서 id 일치.
        List<MailAttachmentDto> attachments = new ArrayList<>();
        collectAttachments(body.path("payload"), attachments);

        return attachments.stream()
                .filter(candidate -> candidate.id().equals(attachmentId))
                .findFirst()
                .orElse(null);
    }

    /** payload tree에서 filename + attachmentId를 가진 part를 첨부로 모으는 수집. */
    private static void collectAttachments(JsonNode part, List<MailAttachmentDto> out) {
        String filename = part.path("filename").asText("");
        String attachmentId = part.path("body").path("attachmentId").asText("");

        if (StringUtils.hasText(filename) && StringUtils.hasText(attachmentId)) {
            out.add(new MailAttachmentDto(
                    attachmentId,
                    filename,
                    part.path("mimeType").asText("application/octet-stream"),
                    part.path("body").path("size").asLong(0)));

            return;
        }

        for (JsonNode child : part.path("parts")) {
            collectAttachments(child, out);
        }
    }

    /** thread 내부에서 internalDate가 가장 큰 message를 최신 message로 간주 */
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

    /** thread 목록 badge는 한 message라도 UNREAD면 unread=true */
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

    /** Gmail label 우선순위로 화면용 folder id를 결정 */
    private static String resolveFolder(JsonNode body) {
        // trash, draft, inbox, sent 순. 없으면 받은편지함.
        if (hasLabel(body, GmailApiConstants.LABEL_TRASH)) {
            return GmailApiConstants.FOLDER_TRASH;
        }
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

    /** labelIds에 해당 라벨이 있는지 확인. */
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

    /** payload headers에서 From·To·Cc·Bcc·Subject·Date만 뽑는 추출. */
    private static HeaderValues extractHeaders(JsonNode payload) {
        // From, To, Cc, Bcc, Subject, Date만.
        String from = "";
        String to = "";
        String cc = "";
        String bcc = "";
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
                    case GmailApiConstants.HEADER_CC -> cc = value;
                    case GmailApiConstants.HEADER_BCC -> bcc = value;
                    case GmailApiConstants.HEADER_SUBJECT -> subject = value;
                    case GmailApiConstants.HEADER_DATE -> date = value;
                    default -> {
                    }
                }
            }
        }

        return new HeaderValues(from, to, cc, bcc, subject, date);
    }

    /** HTML 본문 우선, 없으면 plain text, 둘 다 없으면 빈 문자열 */
    private static BodyContent extractBody(JsonNode payload) {
        // HTML이 있으면 HTML, 없으면 plain.
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

    /** payload tree를 재귀 순회하며 원하는 mimeType 본문을 찾는 탐색. */
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

    /** Gmail body data(base64url)를 UTF-8 문자열로 디코딩. */
    private static String decodeBase64Url(String data) {
        byte[] decoded = Base64.getUrlDecoder().decode(data);

        return new String(decoded, StandardCharsets.UTF_8);
    }

    /** Gmail From 헤더를 표시 이름과 이메일로 나누는 분리. */
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

    /** internalDate가 있으면 우선 사용하고, 없으면 header Date, 둘 다 없으면 현재 시각 fallback */
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

    private record HeaderValues(String from, String to, String cc, String bcc, String subject, String date) {}

    private record ParsedFrom(String displayName, String email) {}

    private record BodyContent(String body, String contentType) {}
}
