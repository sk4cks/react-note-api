package note_api.mail.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import note_api.common.exception.ApiException;
import note_api.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/** OpenAI 호환 chat completions 호출. 키는 서버 env만 사용. */
@Component
public class MailAiClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    /** base URL·키·모델 주입. */
    public MailAiClient(
            ObjectMapper objectMapper,
            @Value("${app.ai.base-url:https://api.openai.com}") String baseUrl,
            @Value("${app.ai.api-key:}") String apiKey,
            @Value("${app.ai.model:gpt-4o-mini}") String model) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.baseUrl = trimSlash(baseUrl);
        this.apiKey = apiKey;
        this.model = model;
    }

    /** system·user 메시지로 한 번 묻고 본문 문자열 수신. */
    public String complete(String system, String user, int maxTokens) {
        if (!StringUtils.hasText(apiKey)) {
            throw new ApiException(ErrorCode.AI_NOT_CONFIGURED);
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("temperature", 0.4);
        root.put("max_tokens", maxTokens);
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        messages.addObject().put("role", "user").put("content", user);

        return post(root);
    }

    /** 대화 목록과 작성 중인 메일을 같이 넣고 답장 수신. */
    public String chat(String system, List<MailAiMessage> history, int maxTokens) {
        if (!StringUtils.hasText(apiKey)) {
            throw new ApiException(ErrorCode.AI_NOT_CONFIGURED);
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("temperature", 0.5);
        root.put("max_tokens", maxTokens);
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);

        for (MailAiMessage message : history) {
            String role = "assistant".equals(message.role()) ? "assistant" : "user";
            messages.addObject().put("role", role).put("content", message.content());
        }

        return post(root);
    }

    /** chat completions POST 후 첫 응답 본문 추출. */
    private String post(ObjectNode body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ApiException(ErrorCode.AI_UPSTREAM);
            }

            JsonNode content = objectMapper.readTree(response.body())
                    .path("choices").path(0).path("message").path("content");

            return content.isTextual() ? content.asText() : "";

        } catch (ApiException ex) {
            throw ex;

        } catch (Exception ex) {
            throw new ApiException(ErrorCode.AI_UPSTREAM, ex);
        }
    }

    /** 끝 슬래시 제거. */
    private static String trimSlash(String value) {
        if (value == null) {
            return "";
        }

        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
