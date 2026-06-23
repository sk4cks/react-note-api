package note_api.mail;

public record MailMessageDetailDto(
        String id,
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
