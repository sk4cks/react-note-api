package note_api.mail;

import note_api.mail.dto.MailAttachmentContent;
import note_api.mail.dto.MailFolderDto;
import note_api.mail.dto.MailMessageDetailDto;
import note_api.mail.dto.MailMessageListDto;
import note_api.mail.dto.SendMailRequest;

import java.util.List;

/** Gmail / IMAP 공통 메일 백엔드. */
public interface MailProvider {

    MailMessageListDto listMessages(String userId, String folder, String pageToken);

    MailMessageDetailDto getMessage(String userId, String folder, String messageId);

    MailAttachmentContent getAttachment(String userId, String folder, String messageId, String attachmentId);

    void sendMessage(String userId, SendMailRequest request);

    List<MailFolderDto> getFolderStats(String userId);
}
