package note_api.common.exception;

public record ErrorResponse(String code, String message) {

    public static ErrorResponse from(ApiException ex) {
        return new ErrorResponse(ex.getErrorCode().name(), ex.getMessage());
    }
}
