package note_api.mail.dto;

import jakarta.validation.Valid;
import org.springframework.util.StringUtils;

import java.util.List;

/** 임시저장. 받는 사람·제목·본문은 비어도 된다. */
public record SaveDraftRequest(
        String id,
        List<String> to,
        List<String> cc,
        List<String> bcc,
        String subject,
        String body,
        List<@Valid MailAttachmentRequest> attachments) {

    public SaveDraftRequest {
        to = copyEmails(to);
        cc = copyEmails(cc);
        bcc = copyEmails(bcc);
        subject = subject == null ? "" : subject;
        body = body == null ? "" : body;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        id = StringUtils.hasText(id) ? id : null;
    }

    /** MIME 생성용. 보내기 validation은 타지 않는다. */
    public SendMailRequest toSendRequest() {
        return new SendMailRequest(to, cc, bcc, subject, body, attachments);
    }

    private static List<String> copyEmails(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
    }
}
