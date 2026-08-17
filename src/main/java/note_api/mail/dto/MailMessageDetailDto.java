package note_api.mail.dto;

import java.util.List;

public record MailMessageDetailDto(
        String id,
        String threadId,
        String folder,
        String from,
        String fromEmail,
        String to,
        String subject,
        String preview,
        String body,
        String bodyContentType,
        String date,
        boolean unread,
        List<MailAttachmentDto> attachments) {

    public MailMessageDetailDto {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    /** 읽음 처리 후 unread만 뒤집은 사본. */
    public MailMessageDetailDto asRead() {
        return new MailMessageDetailDto(
                id, threadId, folder, from, fromEmail, to, subject, preview, body, bodyContentType, date, false, attachments);
    }
}
