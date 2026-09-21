package note_api.common.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private final ObjectMapper objectMapper;

    public GlobalExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
        return ResponseEntity.status(ex.getErrorCode().getStatus())
                .body(ErrorResponse.from(ex));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();

        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fieldError.getField(), describeFieldError(fieldError));
        }

        String message = errors.values().stream()
                .findFirst()
                .orElse("요청 값이 올바르지 않습니다");

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ValidationErrorResponse("VALIDATION_FAILED", message, errors));
    }

    /** 기본 "공백일 수 없습니다" 대신 어느 칸인지 밝힌다. */
    private static String describeFieldError(FieldError fieldError) {
        String raw = fieldError.getDefaultMessage();

        if (StringUtils.hasText(raw) && !isGenericBlankMessage(raw)) {
            return raw;
        }

        return blankMessage(fieldError.getField());
    }

    private static boolean isGenericBlankMessage(String raw) {
        String normalized = raw.trim();

        return "공백일 수 없습니다".equals(normalized)
                || "비어 있을 수 없습니다".equals(normalized)
                || "널이어서는 안됩니다".equals(normalized)
                || "must not be blank".equalsIgnoreCase(normalized)
                || "must not be empty".equalsIgnoreCase(normalized)
                || "must not be null".equalsIgnoreCase(normalized);
    }

    private static String blankMessage(String field) {
        String leaf = field;
        int dot = leaf.lastIndexOf('.');

        if (dot >= 0) {
            leaf = leaf.substring(dot + 1);
        }

        leaf = leaf.replaceAll("\\[\\d+\\]", "");

        return switch (leaf) {
            case "body" -> "본문을 입력해 주세요.";
            case "subject" -> "제목을 입력해 주세요.";
            case "to" -> "받는 사람을 입력해 주세요.";
            case "cc" -> "참조 이메일을 입력해 주세요.";
            case "bcc" -> "숨은참조 이메일을 입력해 주세요.";
            case "filename" -> "첨부 파일 이름이 없습니다.";
            case "contentBase64" -> "첨부 파일 내용이 없습니다.";
            default -> "값을 입력해 주세요.";
        };
    }

    /**
     * Auth Server 4xx/5xx → Boot 기본 에러 필드 + Auth {@code code}/{@code message}.
     */
    @ExceptionHandler(AuthServerException.class)
    public ResponseEntity<Map<String, Object>> handleAuthServer(AuthServerException ex, HttpServletRequest request) {
        HttpStatusCode statusCode = ex.getStatus();
        int status = statusCode.value();
        HttpStatus resolved = HttpStatus.resolve(status);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now(ZoneOffset.UTC).toString());
        body.put("status", status);
        body.put("error", resolved != null ? resolved.getReasonPhrase() : "Error");
        body.put("path", request.getRequestURI());

        // Auth가 준 JSON의 code/message를 프론트가 그대로 쓰게 복사한다.
        if (StringUtils.hasText(ex.getResponseBody())) {
            try {
                JsonNode node = objectMapper.readTree(ex.getResponseBody());
                if (node.hasNonNull("code")) {
                    body.put("code", node.get("code").asText());
                }
                if (node.hasNonNull("message")) {
                    body.put("message", node.get("message").asText());
                }

            } catch (Exception ignored) {
                body.put("message", ex.getResponseBody());
            }
        }

        return ResponseEntity.status(statusCode).body(body);
    }
}
