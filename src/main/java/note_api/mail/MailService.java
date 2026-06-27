package note_api.mail;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import note_api.auth.AuthServerClient;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    private final AuthServerClient authServerClient;
    private final GmailClient gmailClient;

    public List<MailMessageSummaryDto> listMessages(String principal, String folder) {
        String googleToken = authServerClient.fetchGoogleAccessToken(principal);
        return gmailClient.listMessages(googleToken, folder, GmailApiConstants.DEFAULT_LIST_MAX_RESULTS);
    }

    public MailMessageDetailDto getMessage(String principal, String messageId) {
        String googleToken = authServerClient.fetchGoogleAccessToken(principal);
        MailMessageDetailDto detail = gmailClient.getMessage(googleToken, messageId);
        if (!detail.unread()) {
            return detail;
        }
        try {
            gmailClient.markAsRead(googleToken, messageId);
        } catch (RuntimeException ex) {
            log.warn("Failed to mark message as read: {}", messageId, ex);
            return detail;
        }
        
        return new MailMessageDetailDto(
                detail.id(),
                detail.folder(),
                detail.from(),
                detail.fromEmail(),
                detail.to(),
                detail.subject(),
                detail.preview(),
                detail.body(),
                detail.bodyContentType(),
                detail.date(),
                false);
    }

    public void sendMessage(String principal, SendMailRequest request) {
        String googleToken = authServerClient.fetchGoogleAccessToken(principal);
        gmailClient.sendMessage(googleToken, request.to(), request.subject(), request.body());
    }

    public List<MailFolderDto> getFolderStats(String principal) {
        String googleToken = authServerClient.fetchGoogleAccessToken(principal);
        return gmailClient.getFolderStats(googleToken);
    }
}
