package note_api.mail;

import note_api.mail.gmail.GmailMailProvider;
import note_api.mail.imap.ImapMailProvider;
import note_api.mail.dto.MailFolderDto;
import note_api.mail.dto.MailMessageDetailDto;
import note_api.mail.dto.MailMessageListDto;
import note_api.mail.dto.SendMailRequest;
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

    public MailMessageDetailDto getMessage(String principal, String messageId) {
        return mailProvider.getMessage(principal, messageId);
    }

    public void sendMessage(String principal, SendMailRequest request) {
        mailProvider.sendMessage(principal, request);
    }

    public List<MailFolderDto> getFolderStats(String principal) {
        return mailProvider.getFolderStats(principal);
    }
}
