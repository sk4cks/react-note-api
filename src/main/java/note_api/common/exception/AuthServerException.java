package note_api.common.exception;

import org.springframework.http.HttpStatusCode;

/**
 * Auth Server HTTP 4xx/5xx — status + body 를 프론트에 전달하기 위함.
 * {@code ResponseStatusException} 은 Spring 기본 error JSON으로 바뀌어 Auth body가 사라지는 문제 회피.
 */
public class AuthServerException extends RuntimeException {

    private final HttpStatusCode status;
    private final String responseBody;

    /** Auth 응답 status와 body를 그대로 담는 생성. */
    public AuthServerException(HttpStatusCode status, String responseBody) {
        super(responseBody);
        this.status = status;
        this.responseBody = responseBody != null ? responseBody : "";
    }

    /** Auth가 돌려준 HTTP 상태 조회. */
    public HttpStatusCode getStatus() {
        return status;
    }

    /** Auth 응답 본문 조회. 없으면 빈 문자열. */
    public String getResponseBody() {
        return responseBody;
    }
}
