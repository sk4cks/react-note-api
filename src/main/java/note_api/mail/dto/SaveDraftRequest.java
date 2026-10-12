package note_api.mail.dto;

import jakarta.validation.Valid;
import org.springframework.util.StringUtils;

import java.util.List;

/** 임시저장. 받는 사람·제목·본문은 비어도 되는 요청. */
public record SaveDraftRequest(
        String id,
        List<String> to,
        List<String> cc,
        List<String> bcc,
        String subject,
        String body,
        List<@Valid MailAttachmentRequest> attachments) {

    /** 빈 주소는 빼고, 제목·본문 null은 빈 문자열로 맞추는 정규화. */
    public SaveDraftRequest {
        to = copyEmails(to);
        cc = copyEmails(cc);
        bcc = copyEmails(bcc);
        subject = subject == null ? "" : subject;
        body = body == null ? "" : body;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        id = StringUtils.hasText(id) ? id : null;
    }

    /** MIME 생성용 변환. 보내기 validation은 타지 않음. */
    public SendMailRequest toSendRequest() {
        return new SendMailRequest(to, cc, bcc, subject, body, attachments);
    }

    /** null·빈 문자열을 빼고 trim한 주소 목록 복사. */
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
