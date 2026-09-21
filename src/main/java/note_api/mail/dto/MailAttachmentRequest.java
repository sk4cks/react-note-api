package note_api.mail.dto;

import jakarta.validation.constraints.NotBlank;

public record MailAttachmentRequest(
        @NotBlank(message = "첨부 파일 이름이 없습니다.") String filename,
        String contentType,
        @NotBlank(message = "첨부 파일 내용이 없습니다.") String contentBase64) {}
