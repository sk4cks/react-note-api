package note_api.mail.dto;

public record MailMessageSummaryDto(
        String id,
        String folder,
        String from,
        String fromEmail,
        String to,
        String subject,
        String preview,
        String date,
        boolean unread) {}
