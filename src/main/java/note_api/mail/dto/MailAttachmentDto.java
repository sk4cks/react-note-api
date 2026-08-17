package note_api.mail.dto;

/**
 * 메일 상세의 첨부파일 항목. 내용은 다운로드 API로 따로 받는다.
 *
 * @param id 다운로드 키 (IMAP은 MIME part 경로, Gmail은 attachmentId)
 */
public record MailAttachmentDto(
        String id,
        String filename,
        String contentType,
        long size) {}
