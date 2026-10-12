package note_api.mail.dto;

import java.util.List;

/** 메일 히스토리에서 뽑은 수신자 후보. */
public record MailRecipientSuggestion(String email, String displayName) {

    public MailRecipientSuggestion {
        displayName = displayName == null ? "" : displayName;
    }

    /** 이메일과 표시 이름으로 후보 생성. 표시 이름이 없으면 빈 문자열. */
    public static MailRecipientSuggestion of(String email, String displayName) {
        return new MailRecipientSuggestion(email, displayName);
    }
}
