package note_api.mail.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GmailBatchParser 순수 단위 테스트.
 * <p>
 * HTTP 요청 없이 multipart batch 응답 문자열에서 JSON body를 안정적으로 추출하는지 본다.
 */
class GmailBatchParserTest {

    private final GmailBatchParser parser = new GmailBatchParser(new ObjectMapper());

    /** Content-Type 헤더에서 boundary 값만 추출 */
    @Test
    void extractBatchBoundary_returnsBoundaryValue() {
        String boundary = parser.extractBatchBoundary("multipart/mixed; boundary=batch_123");

        assertThat(boundary).isEqualTo("batch_123");
    }

    /** 정상 multipart/mixed batch 응답이면 각 part의 JSON을 순서대로 파싱 */
    @Test
    void parseBatchResponse_parsesMultipartJsonBodies() {
        String responseBody = """
                --batch_123
                Content-Type: application/http

                HTTP/1.1 200 OK
                Content-Type: application/json

                {"id":"a"}
                --batch_123
                Content-Type: application/http

                HTTP/1.1 200 OK
                Content-Type: application/json

                {"id":"b"}
                --batch_123--
                """;

        List<JsonNode> result = parser.parseBatchResponse(responseBody, "batch_123");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).path("id").asText()).isEqualTo("a");
        assertThat(result.get(1).path("id").asText()).isEqualTo("b");
    }

    /** boundary 정보가 없거나 깨져도 JSON 스캔 fallback으로 최대한 복구 */
    @Test
    void parseBatchResponse_fallsBackToJsonScan_whenBoundaryMissing() {
        String responseBody = """
                HTTP/1.1 200 OK
                {"id":"x"}
                ignored text
                {"id":"y"}
                """;

        List<JsonNode> result = parser.parseBatchResponse(responseBody, "");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).path("id").asText()).isEqualTo("x");
        assertThat(result.get(1).path("id").asText()).isEqualTo("y");
    }
}
