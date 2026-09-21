package note_api.mail.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.util.StringUtils;

import java.util.List;

public record SendMailRequest(
        @NotEmpty(message = "받는 사람을 입력해 주세요.")
        List<@NotBlank(message = "받는 사람을 입력해 주세요.") @Email(message = "받는 사람 이메일 형식이 올바르지 않습니다.") String> to,
        List<@NotBlank(message = "참조 이메일을 입력해 주세요.") @Email(message = "참조 이메일 형식이 올바르지 않습니다.") String> cc,
        List<@NotBlank(message = "숨은참조 이메일을 입력해 주세요.") @Email(message = "숨은참조 이메일 형식이 올바르지 않습니다.") String> bcc,
        @NotBlank(message = "제목을 입력해 주세요.") String subject,
        @NotBlank(message = "본문을 입력해 주세요.") String body,
        List<@Valid MailAttachmentRequest> attachments,
        String draftId) {

    public SendMailRequest {
        to = to == null ? List.of() : List.copyOf(to);
        cc = cc == null ? List.of() : List.copyOf(cc);
        bcc = bcc == null ? List.of() : List.copyOf(bcc);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        draftId = StringUtils.hasText(draftId) ? draftId : null;
        body = body == null ? "" : body;

        // 첨부만 있어도 보낸다. 빈 본문은 MIME 자리만 채운다.
        if (!StringUtils.hasText(body) && !attachments.isEmpty()) {
            body = "<p></p>";
        }
    }

    public SendMailRequest(
            List<String> to,
            List<String> cc,
            List<String> bcc,
            String subject,
            String body,
            List<MailAttachmentRequest> attachments) {
        this(to, cc, bcc, subject, body, attachments, null);
    }

    /** 테스트·단건 발송용. To 한 명, CC/BCC 없음. */
    public SendMailRequest(String to, String subject, String body) {
        this(List.of(to), List.of(), List.of(), subject, body, List.of(), null);
    }

    /** 테스트·단건 발송용. To 한 명 + 첨부. */
    public SendMailRequest(String to, String subject, String body, List<MailAttachmentRequest> attachments) {
        this(List.of(to), List.of(), List.of(), subject, body, attachments, null);
    }
}
