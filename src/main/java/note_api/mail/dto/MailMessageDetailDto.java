package note_api.mail.dto;

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
        boolean unread) {}
