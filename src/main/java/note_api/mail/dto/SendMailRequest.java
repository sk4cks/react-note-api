package note_api.mail.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record SendMailRequest(
        @NotBlank @Email String to,
        @NotBlank String subject,
        @NotBlank String body,
        List<@Valid MailAttachmentRequest> attachments) {

    public SendMailRequest {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public SendMailRequest(String to, String subject, String body) {
        this(to, subject, body, List.of());
    }
}
