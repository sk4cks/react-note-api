package note_api.mail;

import note_api.auth.AuthServerClient;
import note_api.mail.dto.MailFolderDto;
import note_api.mail.dto.MailMessageDetailDto;
import note_api.mail.dto.MailMessageListDto;
import note_api.mail.dto.MailMessageSummaryDto;
import note_api.mail.dto.SendMailRequest;
import note_api.mail.gmail.GmailClient;
import note_api.mail.gmail.GmailMailProvider;
import note_api.mail.imap.ImapMailProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * MailService 단위 테스트 — gmail provider 위임 경로.
 */
@ExtendWith(MockitoExtension.class)
class MailServiceTest {

  private static final String PRINCIPAL = "sk4cks@gmail.com";
  private static final String GOOGLE_TOKEN = "google-access-token";

  @Mock private AuthServerClient authServerClient;
  @Mock private GmailClient gmailClient;
  @Mock private ImapMailProvider imapMailProvider;

  private MailService mailService;

  @BeforeEach
  void setUp() {
    GmailMailProvider gmailMailProvider = new GmailMailProvider(authServerClient, gmailClient);
    mailService = new MailService("gmail", gmailMailProvider, imapMailProvider);
  }

  @Test
  void listMessages_fetchesTokenAndDelegatesToGmailClient() {
    MailMessageListDto expected =
        new MailMessageListDto(
            List.of(
                new MailMessageSummaryDto(
                    "msg-1", "inbox", "Alice", "a@x.com", "Hi", "preview", "date", true)),
            "next-page");
    when(authServerClient.fetchGoogleAccessToken(PRINCIPAL)).thenReturn(GOOGLE_TOKEN);
    when(gmailClient.listMessages(GOOGLE_TOKEN, "inbox", 20, null))
        .thenReturn(expected);

    MailMessageListDto result = mailService.listMessages(PRINCIPAL, "inbox", null);

    assertThat(result).isEqualTo(expected);
    verify(authServerClient).fetchGoogleAccessToken(PRINCIPAL);
    verify(gmailClient).listMessages(GOOGLE_TOKEN, "inbox", 20, null);
  }

  @Test
  void getMessage_returnsDetailAsIs_whenAlreadyRead() {
    MailMessageDetailDto readDetail = sampleDetail(false);
    when(authServerClient.fetchGoogleAccessToken(PRINCIPAL)).thenReturn(GOOGLE_TOKEN);
    when(gmailClient.getMessage(GOOGLE_TOKEN, "msg-1")).thenReturn(readDetail);

    MailMessageDetailDto result = mailService.getMessage(PRINCIPAL, "inbox", "msg-1");

    assertThat(result).isEqualTo(readDetail);
    verify(gmailClient).getMessage(GOOGLE_TOKEN, "msg-1");
    verifyNoMoreInteractions(gmailClient);
  }

  @Test
  void getMessage_marksThreadAsRead_whenUnread() {
    MailMessageDetailDto unreadDetail = sampleDetail(true);
    when(authServerClient.fetchGoogleAccessToken(PRINCIPAL)).thenReturn(GOOGLE_TOKEN);
    when(gmailClient.getMessage(GOOGLE_TOKEN, "msg-1")).thenReturn(unreadDetail);

    MailMessageDetailDto result = mailService.getMessage(PRINCIPAL, "inbox", "msg-1");

    verify(gmailClient).markThreadAsRead(GOOGLE_TOKEN, "thread-1");
    assertThat(result.unread()).isFalse();
    assertThat(result.id()).isEqualTo("msg-1");
    assertThat(result.subject()).isEqualTo("Hello");
  }

  @Test
  void getMessage_returnsOriginalDetail_whenMarkAsReadFails() {
    MailMessageDetailDto unreadDetail = sampleDetail(true);
    when(authServerClient.fetchGoogleAccessToken(PRINCIPAL)).thenReturn(GOOGLE_TOKEN);
    when(gmailClient.getMessage(GOOGLE_TOKEN, "msg-1")).thenReturn(unreadDetail);
    doThrow(new RuntimeException("Gmail API error"))
        .when(gmailClient)
        .markThreadAsRead(GOOGLE_TOKEN, "thread-1");

    MailMessageDetailDto result = mailService.getMessage(PRINCIPAL, "inbox", "msg-1");

    assertThat(result).isEqualTo(unreadDetail);
    assertThat(result.unread()).isTrue();
  }

  @Test
  void sendMessage_fetchesTokenAndSends() {
    SendMailRequest request = new SendMailRequest("to@example.com", "Subject", "Body");
    when(authServerClient.fetchGoogleAccessToken(PRINCIPAL)).thenReturn(GOOGLE_TOKEN);

    mailService.sendMessage(PRINCIPAL, request);

    verify(gmailClient).sendMessage(GOOGLE_TOKEN, request);
  }

  @Test
  void getFolderStats_fetchesTokenAndReturnsFolders() {
    List<MailFolderDto> folders = List.of(new MailFolderDto("inbox", "받은편지함", 5));
    when(authServerClient.fetchGoogleAccessToken(PRINCIPAL)).thenReturn(GOOGLE_TOKEN);
    when(gmailClient.getFolderStats(GOOGLE_TOKEN)).thenReturn(folders);

    List<MailFolderDto> result = mailService.getFolderStats(PRINCIPAL);

    assertThat(result).isEqualTo(folders);
    verify(gmailClient).getFolderStats(GOOGLE_TOKEN);
  }

  @Test
  void listMessages_delegatesToImap_whenProviderIsImap() {
    GmailMailProvider gmail = new GmailMailProvider(authServerClient, gmailClient);
    MailService imapService = new MailService("imap", gmail, imapMailProvider);
    MailMessageListDto expected = new MailMessageListDto(List.of(), null);
    when(imapMailProvider.listMessages("sk4cks", "inbox", null)).thenReturn(expected);

    assertThat(imapService.listMessages("sk4cks", "inbox", null)).isEqualTo(expected);
    verify(imapMailProvider).listMessages("sk4cks", "inbox", null);
  }

  private static MailMessageDetailDto sampleDetail(boolean unread) {
    return new MailMessageDetailDto(
        "msg-1",
        "thread-1",
        "inbox",
        "Alice",
        "alice@example.com",
        PRINCIPAL,
        "",
        "",
        "Hello",
        "Preview",
        "Body",
        "text/plain",
        "2026-06-23T10:00:00Z",
        unread,
        List.of());
  }
}
