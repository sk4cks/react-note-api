package note_api.mail.dto;

/** 첨부파일 다운로드 응답 본문. */
public record MailAttachmentContent(
        String filename,
        String contentType,
        byte[] content) {}
