package note_api.mail;

import note_api.mail.dto.MailAttachmentContent;
import note_api.mail.dto.MailFolderDto;
import note_api.mail.dto.MailMessageDetailDto;
import note_api.mail.dto.MailMessageListDto;
import note_api.mail.dto.SendMailRequest;
import note_api.mail.gmail.GmailMailProvider;
import note_api.mail.imap.ImapMailProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

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

    public MailMessageListDto listMessages(String principal, String folder, String pageToken) {
        return mailProvider.listMessages(principal, folder, pageToken);
    }

    public MailMessageDetailDto getMessage(String principal, String folder, String messageId) {
        return mailProvider.getMessage(principal, folder, messageId);
    }

    public MailAttachmentContent getAttachment(
            String principal, String folder, String messageId, String attachmentId) {
        return mailProvider.getAttachment(principal, folder, messageId, attachmentId);
    }

    public void sendMessage(String principal, SendMailRequest request) {
        mailProvider.sendMessage(principal, request);
    }

    public List<MailFolderDto> getFolderStats(String principal) {
        return mailProvider.getFolderStats(principal);
    }
}
