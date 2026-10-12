package note_api.common.exception;

public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    /** 코드 기본 메시지로 던지는 예외. */
    public ApiException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    /** {@link ErrorCode#message(Object...)}로 포맷 인자를 채우는 생성. */
    public ApiException(ErrorCode errorCode, Object... args) {
        super(errorCode.message(args));
        this.errorCode = errorCode;
    }

    /** 원인 예외를 함께 감싸는 생성. */
    public ApiException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.message(), cause);
        this.errorCode = errorCode;
    }

    /** 응답에 실을 에러 코드 조회. */
    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
