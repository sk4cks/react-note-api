package note_api.mail.dto;

public record MailMessageSummaryDto(
        String id,
        String folder,
        String from,
        String fromEmail,
        String subject,
        String preview,
        String date,
        boolean unread) {}
