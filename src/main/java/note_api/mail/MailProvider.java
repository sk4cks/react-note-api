package note_api.mail;

import note_api.mail.dto.MailAttachmentContent;
import note_api.mail.dto.MailFolderDto;
import note_api.mail.dto.MailMessageDetailDto;
import note_api.mail.dto.MailMessageListDto;
import note_api.mail.dto.MailRecipientSuggestion;
import note_api.mail.dto.SaveDraftRequest;
import note_api.mail.dto.SendMailRequest;

import java.util.List;

/** Gmail / IMAP 공통 메일 백엔드. */
public interface MailProvider {

    MailMessageListDto listMessages(String userId, String folder, String pageToken);

    MailMessageDetailDto getMessage(String userId, String folder, String messageId);

    MailAttachmentContent getAttachment(String userId, String folder, String messageId, String attachmentId);

    void sendMessage(String userId, SendMailRequest request);

    /** 임시보관함 저장 후 메시지 id 반환. request.id가 있으면 그 초안 교체. */
    String saveDraft(String userId, SaveDraftRequest request);

    /**
     * 메일 삭제. folder가 trash면 완전 삭제, 그 외는 휴지통 이동.
     */
    void deleteMessages(String userId, String folder, List<String> ids);

    /** 휴지통 메일을 들어오기 전 편지함으로 되돌리는 복원. 표시가 없으면 받은편지함. */
    void restoreMessages(String userId, List<String> ids);

    List<MailFolderDto> getFolderStats(String userId);

    /** 최근 메일 헤더에서 수신자 후보를 모으는 조회. query가 있으면 필터. */
    List<MailRecipientSuggestion> suggestRecipients(String userId, String query);
}
