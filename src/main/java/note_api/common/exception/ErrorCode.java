package note_api.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    MAIL_GOOGLE_NOT_LINKED(HttpStatus.FORBIDDEN, "Google login with Gmail scope is required"),
    MAIL_MAILBOX_NOT_FOUND(HttpStatus.NOT_FOUND, "Mailbox credentials not found for user: %s"),
    MAIL_ATTACHMENT_TOO_MANY(HttpStatus.BAD_REQUEST, "첨부파일은 최대 %d개까지 가능합니다."),
    MAIL_ATTACHMENT_TOO_LARGE(HttpStatus.BAD_REQUEST, "이미지와 첨부파일을 합쳐 %dMB를 넘을 수 없습니다."),
    MAIL_ATTACHMENT_EMPTY(HttpStatus.BAD_REQUEST, "첨부파일 내용이 없습니다."),
    MAIL_ATTACHMENT_ENCODING(HttpStatus.BAD_REQUEST, "첨부파일 인코딩이 올바르지 않습니다."),
    MAIL_ATTACHMENT_FILENAME(HttpStatus.BAD_REQUEST, "첨부파일 이름이 없습니다."),
    MAIL_MESSAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "Message not found: %s"),
    MAIL_ATTACHMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Attachment not found: %s");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** 플레이스홀더가 없는 기본 메시지. */
    public String message() {
        return defaultMessage;
    }

    /** {@link String#format} 인자를 채워 넣은 메시지. */
    public String message(Object... args) {
        return args == null || args.length == 0 ? defaultMessage : String.format(defaultMessage, args);
    }
}
