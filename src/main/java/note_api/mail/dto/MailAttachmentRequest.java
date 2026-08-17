package note_api.mail.dto;

import jakarta.validation.constraints.NotBlank;

public record MailAttachmentRequest(
        @NotBlank String filename,
        String contentType,
        @NotBlank String contentBase64) {}
