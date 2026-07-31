package note_api.common.exception;

import org.springframework.http.HttpStatusCode;

/**
 * Auth Server HTTP 4xx/5xx — status + body 를 프론트에 전달하기 위함.
 * {@code ResponseStatusException} 은 Spring 기본 error JSON 으로 바뀌어 Auth body 가 사라진다.
 */
public class AuthServerException extends RuntimeException {

    private final HttpStatusCode status;
    private final String responseBody;

    public AuthServerException(HttpStatusCode status, String responseBody) {
        super(responseBody);
        this.status = status;
        this.responseBody = responseBody != null ? responseBody : "";
    }

    public HttpStatusCode getStatus() {
        return status;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
