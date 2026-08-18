package note_api.mail;

import note_api.mail.dto.MailAttachmentContent;
import note_api.mail.dto.MailAttachmentDto;
import note_api.mail.dto.MailFolderDto;
import note_api.mail.dto.MailMessageDetailDto;
import note_api.mail.dto.MailMessageListDto;
import note_api.mail.dto.MailMessageSummaryDto;
import note_api.mail.dto.SendMailRequest;
import note_api.common.exception.ApiException;
import note_api.common.exception.ErrorCode;
import note_api.common.exception.GlobalExceptionHandler;
import note_api.config.CorsProperties;
import note_api.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Objects;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MailController 슬라이스 테스트.
 * <p>
 * @SpringBootTest(전체 앱 기동) 대신 @WebMvcTest를 쓰는 이유:
 * - MailController + Security + 예외 처리만 올려서 빠르게 검증
 * - GmailClient, AuthServerClient 등 외부 연동 Bean은 올리지 않음
 * <p>
 * MailService는 @MockBean — HTTP 요청/응답 계약만 검증.
 * /api/mail/** 는 SecurityConfig에서 authenticated — JWT 없으면 401.
 * <p>
 * jwt.getSubject()가 principal로 MailService에 전달됨 (Gmail 토큰 조회 키).
 */
@WebMvcTest(controllers = MailController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@EnableConfigurationProperties(CorsProperties.class)
@TestPropertySource(properties = "app.cors.allowed-origins=http://localhost:8080")
class MailControllerTest {

  private static final String PRINCIPAL = "sk4cks@gmail.com";

  @Autowired private MockMvc mockMvc;

  /** Gmail API·Auth Server 호출 로직은 여기서 검증하지 않음 */
  @MockBean private MailService mailService;

  /** JWT subject를 PRINCIPAL로 고정 — MailService mock 인자와 맞추기 위함 */
  private static RequestPostProcessor authenticatedJwt() {
    return Objects.requireNonNull(
        jwt().jwt(token -> token.subject(PRINCIPAL).claim("scope", "openid profile email gmail")),
        "jwt RequestPostProcessor");
  }

  /** 받은편지함 목록 — folder 기본값 inbox, pageToken 없음 */
  @Test
  void listMessages_returnsMessageList_whenAuthenticated() throws Exception {
    MailMessageSummaryDto summary =
        new MailMessageSummaryDto(
            "msg-1",
            "inbox",
            "Alice",
            "alice@example.com",
            "Hello",
            "Preview text",
            "2026-06-23T10:00:00Z",
            true);
    when(mailService.listMessages(PRINCIPAL, "inbox", null))
        .thenReturn(new MailMessageListDto(List.of(summary), "next-token"));

    mockMvc
        .perform(get("/api/mail/messages").with(authenticatedJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.messages[0].id").value("msg-1"))
        .andExpect(jsonPath("$.messages[0].subject").value("Hello"))
        .andExpect(jsonPath("$.messages[0].unread").value(true))
        .andExpect(jsonPath("$.nextPageToken").value("next-token"));
  }

  /** Authorization 헤더·JWT 없이 호출하면 401 Unauthorized */
  @Test
  void listMessages_returnsUnauthorized_whenNotAuthenticated() throws Exception {
    mockMvc.perform(get("/api/mail/messages")).andExpect(status().isUnauthorized());
  }

  /**
   * Google 계정/Gmail scope 미연동 시 MailService가 예외 throw.
   * GlobalExceptionHandler가 403 + code JSON으로 변환하는지 검증.
   */
  @Test
  void listMessages_returnsForbidden_whenGoogleNotLinked() throws Exception {
    when(mailService.listMessages(any(), any(), any()))
        .thenThrow(new ApiException(ErrorCode.MAIL_GOOGLE_NOT_LINKED));

    mockMvc
        .perform(get("/api/mail/messages").with(authenticatedJwt()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("MAIL_GOOGLE_NOT_LINKED"));
  }

  @Test
  void getMessage_returnsNotFound_whenMessageMissing() throws Exception {
    when(mailService.getMessage(PRINCIPAL, "inbox", "missing"))
        .thenThrow(new ApiException(ErrorCode.MAIL_MESSAGE_NOT_FOUND, "missing"));

    mockMvc
        .perform(get("/api/mail/messages/missing").with(authenticatedJwt()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MAIL_MESSAGE_NOT_FOUND"));
  }

  /** 메일 상세 — path variable id를 MailService에 그대로 전달 */
  @Test
  void getMessage_returnsDetail_whenAuthenticated() throws Exception {
    MailMessageDetailDto detail =
        new MailMessageDetailDto(
            "msg-1",
            "thread-1",
            "inbox",
            "Alice",
            "alice@example.com",
            PRINCIPAL,
            "Hello",
            "Preview",
            "<p>Body</p>",
            "text/html",
            "2026-06-23T10:00:00Z",
            false,
            List.of(new MailAttachmentDto("1", "report.pdf", "application/pdf", 1024)));
    when(mailService.getMessage(PRINCIPAL, "inbox", "msg-1")).thenReturn(detail);

    mockMvc
        .perform(get("/api/mail/messages/msg-1").with(authenticatedJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("msg-1"))
        .andExpect(jsonPath("$.threadId").value("thread-1"))
        .andExpect(jsonPath("$.body").value("<p>Body</p>"))
        .andExpect(jsonPath("$.unread").value(false))
        .andExpect(jsonPath("$.attachments[0].id").value("1"))
        .andExpect(jsonPath("$.attachments[0].filename").value("report.pdf"));
  }

  /** 첨부 다운로드 — 파일명은 RFC 5987로 인코딩해 Content-Disposition에 실린다 */
  @Test
  void getAttachment_returnsBytesWithFilename() throws Exception {
    when(mailService.getAttachment(PRINCIPAL, "sent", "msg-1", "1"))
        .thenReturn(new MailAttachmentContent("보고서.pdf", "application/pdf", new byte[] {1, 2, 3}));

    mockMvc
        .perform(get("/api/mail/messages/msg-1/attachments/1?folder=sent").with(authenticatedJwt()))
        .andExpect(status().isOk())
        .andExpect(content().contentType("application/pdf"))
        .andExpect(header().string("Content-Disposition", containsString("UTF-8''")))
        .andExpect(content().bytes(new byte[] {1, 2, 3}));
  }

  /** 폴더별 메일 개수 (inbox badge 등) */
  @Test
  void getFolders_returnsFolderList_whenAuthenticated() throws Exception {
    when(mailService.getFolderStats(PRINCIPAL))
        .thenReturn(List.of(new MailFolderDto("inbox", "받은편지함", 3)));

    mockMvc
        .perform(get("/api/mail/folders").with(authenticatedJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("inbox"))
        .andExpect(jsonPath("$[0].label").value("받은편지함"))
        .andExpect(jsonPath("$[0].count").value(3));
  }

  /** 메일 발송 — void 반환이므로 200 OK, MailService 호출 여부 verify */
  @Test
  void sendMail_succeeds_whenRequestValid() throws Exception {
    mockMvc
        .perform(
            post("/api/mail/send")
                .with(authenticatedJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"to":"recipient@example.com","subject":"Test","body":"Hello"}
                    """))
        .andExpect(status().isOk());

    verify(mailService)
        .sendMessage(
            eq(PRINCIPAL),
            eq(new SendMailRequest("recipient@example.com", "Test", "Hello")));
  }

  /** @Valid — 잘못된 이메일 형식이면 400 Bad Request (MailService 호출 전 차단) */
  @Test
  void sendMail_returnsBadRequest_whenEmailInvalid() throws Exception {
    mockMvc
        .perform(
            post("/api/mail/send")
                .with(authenticatedJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"to":"not-an-email","subject":"Test","body":"Hello"}
                    """))
        .andExpect(status().isBadRequest());
  }
}
