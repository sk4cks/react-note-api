package note_api.mail;

import note_api.mail.dto.MailAttachmentContent;
import note_api.mail.dto.MailFolderDto;
import note_api.mail.dto.MailMessageDetailDto;
import note_api.mail.dto.MailMessageListDto;
import note_api.mail.dto.MailRecipientSuggestion;
import note_api.mail.dto.SendMailRequest;
import note_api.mail.gmail.GmailMailProvider;
import note_api.mail.imap.ImapMailProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/** Gmail / IMAP 구현체 중 하나를 골라 프론트 메일 API에 넘긴다. */
@Service
public class MailService {

    private final MailProvider mailProvider;

    public MailService(
            @Value("${app.mail.provider:gmail}") String provider,
            GmailMailProvider gmailMailProvider,
            ImapMailProvider imapMailProvider) {
        String normalized = provider == null ? "gmail" : provider.trim().toLowerCase(Locale.ROOT);
        this.mailProvider = "imap".equals(normalized) ? imapMailProvider : gmailMailProvider;
    }

    /** 폴더 메일 목록. pageToken이면 다음 페이지. */
    public MailMessageListDto listMessages(String principal, String folder, String pageToken) {
        return mailProvider.listMessages(principal, folder, pageToken);
    }

    /** 메일 한 통. */
    public MailMessageDetailDto getMessage(String principal, String folder, String messageId) {
        return mailProvider.getMessage(principal, folder, messageId);
    }

    /** 첨부 파일 바이트. */
    public MailAttachmentContent getAttachment(
            String principal, String folder, String messageId, String attachmentId) {
        return mailProvider.getAttachment(principal, folder, messageId, attachmentId);
    }

    /** 메일 발송. */
    public void sendMessage(String principal, SendMailRequest request) {
        mailProvider.sendMessage(principal, request);
    }

    /** 편지함 건수(뱃지). */
    public List<MailFolderDto> getFolderStats(String principal) {
        return mailProvider.getFolderStats(principal);
    }

    /** 최근 메일 헤더 기반 수신자 제안. */
    public List<MailRecipientSuggestion> suggestRecipients(String principal, String query) {
        return mailProvider.suggestRecipients(principal, query);
    }
}
