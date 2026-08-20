package note_api.mail.gmail;

import com.fasterxml.jackson.databind.ObjectMapper;
import note_api.mail.dto.MailMessageDetailDto;
import note_api.mail.dto.MailMessageSummaryDto;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GmailMessageParser 순수 단위 테스트.
 * <p>
 * header/body/folder/unread 규칙을 JSON fixture만으로 검증해서 GmailClient HTTP 테스트를 가볍게 유지한다.
 */
class GmailMessageParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GmailMessageParser parser = new GmailMessageParser();

    /** HTML 본문이 있으면 plain text보다 우선하고 From/Subject/folder를 함께 파싱 */
    @Test
    void toDetail_prefersHtmlBodyAndParsesHeaders() throws Exception {
        String htmlBody = "<p>Hello</p>";
        String plainBody = "Hello";
        String encodedHtml = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(htmlBody.getBytes());
        String encodedPlain = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(plainBody.getBytes());

        MailMessageDetailDto detail =
                parser.toDetail(
                        objectMapper.readTree(
                                """
                                {
                                  "id": "msg-1",
                                  "threadId": "thread-1",
                                  "snippet": "Preview",
                                  "internalDate": "1719835200000",
                                  "labelIds": ["INBOX", "UNREAD"],
                                  "payload": {
                                    "headers": [
                                      {"name": "From", "value": "Alice <alice@example.com>"},
                                      {"name": "To", "value": "sk4cks@gmail.com"},
                                      {"name": "Cc", "value": "cc@example.com"},
                                      {"name": "Bcc", "value": "bcc@example.com"},
                                      {"name": "Subject", "value": "Hello"}
                                    ],
                                    "parts": [
                                      {
                                        "mimeType": "text/plain",
                                        "body": {"data": "%s"}
                                      },
                                      {
                                        "mimeType": "text/html",
                                        "body": {"data": "%s"}
                                      }
                                    ]
                                  }
                                }
                                """.formatted(encodedPlain, encodedHtml)));

        assertThat(detail.id()).isEqualTo("msg-1");
        assertThat(detail.threadId()).isEqualTo("thread-1");
        assertThat(detail.folder()).isEqualTo("inbox");
        assertThat(detail.from()).isEqualTo("Alice");
        assertThat(detail.fromEmail()).isEqualTo("alice@example.com");
        assertThat(detail.to()).isEqualTo("sk4cks@gmail.com");
        assertThat(detail.cc()).isEqualTo("cc@example.com");
        assertThat(detail.bcc()).isEqualTo("bcc@example.com");
        assertThat(detail.subject()).isEqualTo("Hello");
        assertThat(detail.body()).isEqualTo(htmlBody);
        assertThat(detail.bodyContentType()).isEqualTo("text/html");
        assertThat(detail.unread()).isTrue();
    }

    /** thread 안 여러 message 중 최신 message를 대표로 쓰고 unread는 전체 message를 훑어 계산 */
    @Test
    void toThreadSummary_usesLatestMessageAndDetectsUnreadAcrossMessages() throws Exception {
        MailMessageSummaryDto summary =
                parser.toThreadSummary(
                        "inbox",
                        objectMapper.readTree(
                                """
                                {
                                  "id": "thread-1",
                                  "snippet": "Thread preview",
                                  "messages": [
                                    {
                                      "id": "msg-older",
                                      "internalDate": "1000",
                                      "labelIds": ["UNREAD"],
                                      "payload": {
                                        "headers": [
                                          {"name": "From", "value": "Old <old@example.com>"},
                                          {"name": "Subject", "value": "Old subject"}
                                        ]
                                      }
                                    },
                                    {
                                      "id": "msg-latest",
                                      "internalDate": "2000",
                                      "snippet": "Latest preview",
                                      "payload": {
                                        "headers": [
                                          {"name": "From", "value": "Latest <latest@example.com>"},
                                          {"name": "Subject", "value": "Latest subject"}
                                        ]
                                      }
                                    }
                                  ]
                                }
                                """));

        assertThat(summary.id()).isEqualTo("msg-latest");
        assertThat(summary.from()).isEqualTo("Latest");
        assertThat(summary.fromEmail()).isEqualTo("latest@example.com");
        assertThat(summary.subject()).isEqualTo("Latest subject");
        assertThat(summary.preview()).isEqualTo("Thread preview");
        assertThat(summary.unread()).isTrue();
    }

    /** From 헤더가 비어 있으면 UI 기본값으로 Unknown / 빈 email 사용 */
    @Test
    void toDetail_returnsUnknownWhenFromHeaderMissing() throws Exception {
        MailMessageDetailDto detail =
                parser.toDetail(
                        objectMapper.readTree(
                                """
                                {
                                  "id": "msg-2",
                                  "threadId": "thread-2",
                                  "labelIds": ["SENT"],
                                  "payload": {
                                    "headers": [
                                      {"name": "To", "value": "target@example.com"},
                                      {"name": "Subject", "value": "No from"}
                                    ],
                                    "mimeType": "text/plain",
                                    "body": {"data": "SGVsbG8"}
                                  }
                                }
                                """));

        assertThat(detail.folder()).isEqualTo("sent");
        assertThat(detail.from()).isEqualTo("Unknown");
        assertThat(detail.fromEmail()).isEmpty();
    }
}
