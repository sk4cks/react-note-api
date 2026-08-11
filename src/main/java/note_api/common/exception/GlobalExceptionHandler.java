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

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        String message = errors.values().stream()
                .findFirst()
                .orElse("요청 값이 올바르지 않습니다");

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ValidationErrorResponse("VALIDATION_FAILED", message, errors));
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
