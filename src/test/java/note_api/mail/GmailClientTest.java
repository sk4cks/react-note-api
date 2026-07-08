package note_api.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import note_api.mail.dto.MailFolderDto;
import note_api.mail.dto.MailMessageDetailDto;
import note_api.mail.dto.MailMessageListDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * GmailClient contract 테스트.
 * <p>
 * MockRestServiceServer로 Gmail API 요청 URL/헤더/body와 대표 응답 매핑을 검증한다.
 * 상세 파싱 규칙 자체는 GmailBatchParserTest, GmailMessageParserTest에서 더 작게 검증한다.
 */
class GmailClientTest {

    private static final String ACCESS_TOKEN = "google-token";

    private RestTemplate restTemplate;
    private ObjectMapper objectMapper;
    private MockRestServiceServer server;
    private GmailClient gmailClient;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        objectMapper = new ObjectMapper();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        gmailClient = new GmailClient(restTemplate, objectMapper);
    }

    /** inbox 목록 조회 — primary query + batch thread metadata 요청까지 이어지는 흐름 */
    @Test
    void listMessages_inboxRequestsPrimaryQueryAndMapsThreadSummary() {
        server
                .expect(requestTo(containsString("/threads?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                .andExpect(requestTo(containsString("maxResults=20")))
                .andExpect(requestTo(containsString("q=in:inbox%20category:primary")))
                .andRespond(withSuccess("""
                        {
                          "threads": [{"id": "thread-1"}],
                          "nextPageToken": "next-1"
                        }
                        """, MediaType.APPLICATION_JSON));

        server
                .expect(requestTo(GmailApiConstants.BATCH_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                .andExpect(content().string(containsString("GET /gmail/v1/users/me/threads/thread-1?")))
                .andExpect(content().string(containsString(GmailApiConstants.METADATA_QUERY)))
                .andRespond(
                        withSuccess(
                                """
                                --batch_resp
                                Content-Type: application/http

                                HTTP/1.1 200 OK
                                Content-Type: application/json

                                {
                                  "id":"thread-1",
                                  "snippet":"Thread preview",
                                  "messages":[
                                    {
                                      "id":"msg-1",
                                      "internalDate":"1719835200000",
                                      "labelIds":["UNREAD"],
                                      "payload":{
                                        "headers":[
                                          {"name":"From","value":"Alice <alice@example.com>"},
                                          {"name":"Subject","value":"Hello"},
                                          {"name":"Date","value":"Mon, 01 Jul 2024 12:00:00 +0900"}
                                        ]
                                      }
                                    }
                                  ]
                                }
                                --batch_resp--
                                """,
                                MediaType.parseMediaType("multipart/mixed; boundary=batch_resp")));

        MailMessageListDto result = gmailClient.listMessages(ACCESS_TOKEN, "inbox", 20, null);

        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().get(0).id()).isEqualTo("msg-1");
        assertThat(result.messages().get(0).from()).isEqualTo("Alice");
        assertThat(result.messages().get(0).unread()).isTrue();
        assertThat(result.nextPageToken()).isEqualTo("next-1");
        server.verify();
    }

    /** 메일 상세 조회 — format=full 요청 후 HTML body / unread / header 매핑 */
    @Test
    void getMessage_requestsFullMessageAndMapsDetail() {
        String encodedHtml =
                Base64.getUrlEncoder().withoutPadding()
                        .encodeToString("<p>Hello</p>".getBytes(StandardCharsets.UTF_8));

        server
                .expect(requestTo(containsString("/messages/msg-1?format=full")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                .andRespond(withSuccess("""
                        {
                          "id":"msg-1",
                          "threadId":"thread-1",
                          "snippet":"Preview",
                          "internalDate":"1719835200000",
                          "labelIds":["INBOX","UNREAD"],
                          "payload":{
                            "headers":[
                              {"name":"From","value":"Alice <alice@example.com>"},
                              {"name":"To","value":"sk4cks@gmail.com"},
                              {"name":"Subject","value":"Hello"}
                            ],
                            "parts":[
                              {
                                "mimeType":"text/html",
                                "body":{"data":"%s"}
                              }
                            ]
                          }
                        }
                        """.formatted(encodedHtml), MediaType.APPLICATION_JSON));

        MailMessageDetailDto detail = gmailClient.getMessage(ACCESS_TOKEN, "msg-1");

        assertThat(detail.id()).isEqualTo("msg-1");
        assertThat(detail.threadId()).isEqualTo("thread-1");
        assertThat(detail.body()).isEqualTo("<p>Hello</p>");
        assertThat(detail.bodyContentType()).isEqualTo("text/html");
        assertThat(detail.unread()).isTrue();
        server.verify();
    }

    /** thread 읽음 처리 — UNREAD label 제거 payload가 modify endpoint로 전송 */
    @Test
    void markThreadAsRead_postsModifyPayload() {
        server
                .expect(requestTo(GmailApiConstants.USERS_ME_BASE + "/threads/thread-1/modify"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                .andExpect(request -> {
                    String requestBody =
                            ((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8);
                    JsonNode json = objectMapper.readTree(requestBody);
                    assertThat(json.path("removeLabelIds").size()).isEqualTo(1);
                    assertThat(json.path("removeLabelIds").get(0).asText()).isEqualTo("UNREAD");
                })
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        gmailClient.markThreadAsRead(ACCESS_TOKEN, "thread-1");

        server.verify();
    }

    /** 메일 발송 — raw MIME을 base64url로 감싼 JSON payload 전송 */
    @Test
    void sendMessage_postsBase64UrlEncodedMime() {
        server
                .expect(requestTo(GmailApiConstants.USERS_ME_BASE + "/messages/send"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                .andExpect(request -> {
                    String requestBody =
                            ((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8);
                    JsonNode json = objectMapper.readTree(requestBody);
                    String raw = json.path("raw").asText();
                    String mime =
                            new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);

                    assertThat(mime).contains("MIME-Version: 1.0");
                    assertThat(mime).contains("To: recipient@example.com");
                    assertThat(mime).contains("Subject: Test subject");
                    assertThat(mime).contains("Content-Type: text/plain; charset=UTF-8");
                })
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        gmailClient.sendMessage(ACCESS_TOKEN, "recipient@example.com", "Test subject", "Hello body");

        server.verify();
    }

    /**
     * 폴더 통계 — draft label batch 조회 + inbox unread thread count paging.
     * 현재 구현 기준 sent count는 별도 조회하지 않으므로 0으로 내려간다.
     */
    @Test
    void getFolderStats_fetchesDraftLabelAndCountsUnreadInboxAcrossPages() {
        server
                .expect(requestTo(GmailApiConstants.BATCH_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                .andExpect(content().string(containsString("GET /gmail/v1/users/me/labels/DRAFT")))
                .andRespond(
                        withSuccess(
                                """
                                --batch_resp
                                Content-Type: application/http

                                HTTP/1.1 200 OK
                                Content-Type: application/json

                                {
                                  "id":"DRAFT",
                                  "threadsTotal":7
                                }
                                --batch_resp--
                                """,
                                MediaType.parseMediaType("multipart/mixed; boundary=batch_resp")));

        server
                .expect(requestTo(containsString("/threads?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(requestTo(containsString("q=in:inbox%20category:primary%20is:unread")))
                .andExpect(requestTo(containsString("maxResults=500")))
                .andRespond(
                        withSuccess(
                                """
                                {
                                  "threads":[{"id":"t1"},{"id":"t2"}],
                                  "nextPageToken":"page-2"
                                }
                                """,
                                MediaType.APPLICATION_JSON));

        server
                .expect(requestTo(containsString("/threads?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(requestTo(containsString("q=in:inbox%20category:primary%20is:unread")))
                .andExpect(requestTo(containsString("pageToken=page-2")))
                .andRespond(
                        withSuccess(
                                """
                                {
                                  "threads":[{"id":"t3"}]
                                }
                                """,
                                MediaType.APPLICATION_JSON));

        List<MailFolderDto> folders = gmailClient.getFolderStats(ACCESS_TOKEN);

        assertThat(folders).hasSize(3);
        assertThat(folders.get(0).id()).isEqualTo("inbox");
        assertThat(folders.get(0).count()).isEqualTo(3);
        assertThat(folders.get(1).id()).isEqualTo("sent");
        assertThat(folders.get(1).count()).isEqualTo(0);
        assertThat(folders.get(2).id()).isEqualTo("draft");
        assertThat(folders.get(2).count()).isEqualTo(7);
        server.verify();
    }
}
