package note_api.common;

import java.util.Map;

public record ValidationErrorResponse(String code, String message, Map<String, String> errors) {}
