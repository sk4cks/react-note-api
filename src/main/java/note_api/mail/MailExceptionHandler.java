package note_api.mail;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class MailExceptionHandler {

    @ExceptionHandler(MailGoogleNotLinkedException.class)
    public ResponseEntity<Map<String, String>> handleNotLinked(MailGoogleNotLinkedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of(
                        "code", "MAIL_GOOGLE_NOT_LINKED",
                        "message", ex.getMessage()));
    }
}
