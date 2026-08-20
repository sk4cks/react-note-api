package note_api.mail.dto;

import java.util.List;

/** 메일 히스토리에서 뽑은 수신자 후보. */
public record MailRecipientSuggestion(String email, String displayName) {

    public MailRecipientSuggestion {
        displayName = displayName == null ? "" : displayName;
    }

    public static MailRecipientSuggestion of(String email, String displayName) {
        return new MailRecipientSuggestion(email, displayName);
    }
}
