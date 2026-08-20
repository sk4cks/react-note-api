package note_api.mail.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record SendMailRequest(
        @NotEmpty List<@NotBlank @Email String> to,
        List<@NotBlank @Email String> cc,
        List<@NotBlank @Email String> bcc,
        @NotBlank String subject,
        @NotBlank String body,
        List<@Valid MailAttachmentRequest> attachments) {

    public SendMailRequest {
        to = to == null ? List.of() : List.copyOf(to);
        cc = cc == null ? List.of() : List.copyOf(cc);
        bcc = bcc == null ? List.of() : List.copyOf(bcc);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    /** 테스트·단건 발송용. To 한 명, CC/BCC 없음. */
    public SendMailRequest(String to, String subject, String body) {
        this(List.of(to), List.of(), List.of(), subject, body, List.of());
    }

    /** 테스트·단건 발송용. To 한 명 + 첨부. */
    public SendMailRequest(String to, String subject, String body, List<MailAttachmentRequest> attachments) {
        this(List.of(to), List.of(), List.of(), subject, body, attachments);
    }
}
