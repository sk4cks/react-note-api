package note_api.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    MAIL_GOOGLE_NOT_LINKED(HttpStatus.FORBIDDEN, "Google login with Gmail scope is required"),
    MAIL_MAILBOX_NOT_FOUND(HttpStatus.NOT_FOUND, "Mailbox credentials not found"),
    MAIL_INVALID_ATTACHMENT(HttpStatus.BAD_REQUEST, "Invalid mail attachment"),
    MAIL_MESSAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "Message not found"),
    MAIL_ATTACHMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Attachment not found");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
