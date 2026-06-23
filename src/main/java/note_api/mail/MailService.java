package note_api.mail;

import lombok.RequiredArgsConstructor;
import note_api.auth.AuthServerClient;
import org.springframework.stereotype.Service;

import java.util.List;

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
        return gmailClient.getMessage(googleToken, messageId);
    }

    public void sendMessage(String principal, SendMailRequest request) {
        String googleToken = authServerClient.fetchGoogleAccessToken(principal);
        gmailClient.sendMessage(googleToken, request.to(), request.subject(), request.body());
    }
}
