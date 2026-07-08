package note_api.mail;

import note_api.auth.AuthServerClient;
import note_api.mail.dto.MailFolderDto;
import note_api.mail.dto.MailMessageDetailDto;
import note_api.mail.dto.MailMessageListDto;
import note_api.mail.dto.MailMessageSummaryDto;
import note_api.mail.dto.SendMailRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * MailService 단위 테스트.
 * <p>
 * @WebMvcTest / @SpringBootTest 없이 Mockito만 사용:
 * - AuthServerClient, GmailClient를 @Mock으로 대체
 * - principal → Google access token 조회 → Gmail API 위임 흐름 검증
 * - HTTP·Security 계층은 MailControllerTest에서 검증
 */
@ExtendWith(MockitoExtension.class)
class MailServiceTest {

  private static final String PRINCIPAL = "sk4cks@gmail.com";
  private static final String GOOGLE_TOKEN = "google-access-token";

  @Mock private AuthServerClient authServerClient;
  @Mock private GmailClient gmailClient;

  @InjectMocks private MailService mailService;

  /** Auth Server에서 Google token 조회 후 Gmail 목록 API에 위임 */
  @Test
  void listMessages_fetchesTokenAndDelegatesToGmailClient() {
    MailMessageListDto expected =
        new MailMessageListDto(
            List.of(
                new MailMessageSummaryDto(
                    "msg-1", "inbox", "Alice", "a@x.com", "Hi", "preview", "date", true)),
            "next-page");
    when(authServerClient.fetchGoogleAccessToken(PRINCIPAL)).thenReturn(GOOGLE_TOKEN);
    when(gmailClient.listMessages(GOOGLE_TOKEN, "inbox", GmailApiConstants.DEFAULT_LIST_MAX_RESULTS, null))
        .thenReturn(expected);

    MailMessageListDto result = mailService.listMessages(PRINCIPAL, "inbox", null);

    assertThat(result).isEqualTo(expected);
    verify(authServerClient).fetchGoogleAccessToken(PRINCIPAL);
    verify(gmailClient).listMessages(GOOGLE_TOKEN, "inbox", GmailApiConstants.DEFAULT_LIST_MAX_RESULTS, null);
  }

  /** 이미 읽은 메일이면 markThreadAsRead 호출 없이 그대로 반환 */
  @Test
  void getMessage_returnsDetailAsIs_whenAlreadyRead() {
    MailMessageDetailDto readDetail = sampleDetail(false);
    when(authServerClient.fetchGoogleAccessToken(PRINCIPAL)).thenReturn(GOOGLE_TOKEN);
    when(gmailClient.getMessage(GOOGLE_TOKEN, "msg-1")).thenReturn(readDetail);

    MailMessageDetailDto result = mailService.getMessage(PRINCIPAL, "msg-1");

    assertThat(result).isEqualTo(readDetail);
    verify(gmailClient).getMessage(GOOGLE_TOKEN, "msg-1");
    verifyNoMoreInteractions(gmailClient);
  }

  /**
   * 안 읽은 메일이면 thread를 읽음 처리한 뒤 unread=false 로 반환.
   * 상세 열람 시 자동 읽음 처리 UX.
   */
  @Test
  void getMessage_marksThreadAsRead_whenUnread() {
    MailMessageDetailDto unreadDetail = sampleDetail(true);
    when(authServerClient.fetchGoogleAccessToken(PRINCIPAL)).thenReturn(GOOGLE_TOKEN);
    when(gmailClient.getMessage(GOOGLE_TOKEN, "msg-1")).thenReturn(unreadDetail);

    MailMessageDetailDto result = mailService.getMessage(PRINCIPAL, "msg-1");

    verify(gmailClient).markThreadAsRead(GOOGLE_TOKEN, "thread-1");
    assertThat(result.unread()).isFalse();
    assertThat(result.id()).isEqualTo("msg-1");
    assertThat(result.subject()).isEqualTo("Hello");
  }

  /**
   * 읽음 처리 실패 시에도 상세 본문은 반환 (로그만 남기고 swallow).
   * Gmail API 일시 오류로 상세 조회 자체가 실패하지 않도록.
   */
  @Test
  void getMessage_returnsOriginalDetail_whenMarkAsReadFails() {
    MailMessageDetailDto unreadDetail = sampleDetail(true);
    when(authServerClient.fetchGoogleAccessToken(PRINCIPAL)).thenReturn(GOOGLE_TOKEN);
    when(gmailClient.getMessage(GOOGLE_TOKEN, "msg-1")).thenReturn(unreadDetail);
    doThrow(new RuntimeException("Gmail API error"))
        .when(gmailClient)
        .markThreadAsRead(GOOGLE_TOKEN, "thread-1");

    MailMessageDetailDto result = mailService.getMessage(PRINCIPAL, "msg-1");

    assertThat(result).isEqualTo(unreadDetail);
    assertThat(result.unread()).isTrue();
  }

  /** 발송 — token 조회 후 Gmail send API 호출 */
  @Test
  void sendMessage_fetchesTokenAndSends() {
    SendMailRequest request = new SendMailRequest("to@example.com", "Subject", "Body");
    when(authServerClient.fetchGoogleAccessToken(PRINCIPAL)).thenReturn(GOOGLE_TOKEN);

    mailService.sendMessage(PRINCIPAL, request);

    verify(gmailClient).sendMessage(GOOGLE_TOKEN, "to@example.com", "Subject", "Body");
  }

  /** 폴더별 스레드 수 — inbox badge 등에 사용 */
  @Test
  void getFolderStats_fetchesTokenAndReturnsFolders() {
    List<MailFolderDto> folders = List.of(new MailFolderDto("inbox", "받은편지함", 5));
    when(authServerClient.fetchGoogleAccessToken(PRINCIPAL)).thenReturn(GOOGLE_TOKEN);
    when(gmailClient.getFolderStats(GOOGLE_TOKEN)).thenReturn(folders);

    List<MailFolderDto> result = mailService.getFolderStats(PRINCIPAL);

    assertThat(result).isEqualTo(folders);
    verify(gmailClient).getFolderStats(GOOGLE_TOKEN);
  }

  private static MailMessageDetailDto sampleDetail(boolean unread) {
    return new MailMessageDetailDto(
        "msg-1",
        "thread-1",
        "inbox",
        "Alice",
        "alice@example.com",
        PRINCIPAL,
        "Hello",
        "Preview",
        "Body",
        "text/plain",
        "2026-06-23T10:00:00Z",
        unread);
  }
}
