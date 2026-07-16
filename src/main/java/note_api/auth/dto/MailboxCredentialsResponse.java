package note_api.auth.dto;

public record MailboxCredentialsResponse(
        String mailAddress,
        String password,
        String imapHost,
        int imapPort,
        String smtpHost,
        int smtpPort) {}
