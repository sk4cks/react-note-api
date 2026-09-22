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

    /** 임시보관함에 저장하고 메시지 id를 돌려준다. request.id가 있으면 그 초안을 교체한다. */
    String saveDraft(String userId, SaveDraftRequest request);

    /**
     * 메일을 지운다. folder가 trash면 완전 삭제, 그 외는 휴지통으로 옮긴다.
     */
    void deleteMessages(String userId, String folder, List<String> ids);

    /** 휴지통 메일을 들어오기 전 편지함으로 되돌린다. 표시가 없으면 받은편지함. */
    void restoreMessages(String userId, List<String> ids);

    List<MailFolderDto> getFolderStats(String userId);

    /** 최근 메일 헤더에서 수신자 후보를 모은다. query가 있으면 필터. */
    List<MailRecipientSuggestion> suggestRecipients(String userId, String query);
}
