package note_api.common.exception;

public record ErrorResponse(String code, String message) {

    /** ApiException의 코드 이름과 메시지를 응답 본문으로 변환. */
    public static ErrorResponse from(ApiException ex) {
        return new ErrorResponse(ex.getErrorCode().name(), ex.getMessage());
    }
}
