package note_api.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gmail batch(multipart/mixed) 응답에서 JSON body만 추출한다.
 */
final class GmailBatchParser {

    private final ObjectMapper objectMapper;

    GmailBatchParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Gmail batch 응답은 정상적으로는 multipart/mixed + boundary 구조다.
     * 다만 boundary가 없거나 일부가 깨진 응답도 대비해서 JSON 스캔 fallback을 둔다.
     */
    List<JsonNode> parseBatchResponse(String responseBody, String boundary) {
        if (!StringUtils.hasText(responseBody)) {
            return List.of();
        }

        List<JsonNode> result = new ArrayList<>();
        if (StringUtils.hasText(boundary)) {
            String[] parts = responseBody.split("--" + Pattern.quote(boundary));
            for (String part : parts) {
                parseJsonFromBatchPart(part).ifPresent(json -> {
                    try {
                        result.add(objectMapper.readTree(json));
                    } catch (Exception ex) {
                        throw new IllegalStateException("Failed to parse Gmail batch part", ex);
                    }
                });
            }
            if (!result.isEmpty()) {
                return result;
            }
        }

        return parseBatchJsonFallback(responseBody);
    }

    /** Content-Type: multipart/mixed; boundary=... 에서 boundary만 꺼낸다. */
    String extractBatchBoundary(String contentType) {
        Matcher matcher = GmailApiConstants.BATCH_BOUNDARY.matcher(contentType);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    /** multipart 파싱이 불가능할 때 응답 전체에서 JSON object를 순차적으로 스캔 */
    private List<JsonNode> parseBatchJsonFallback(String responseBody) {
        List<JsonNode> result = new ArrayList<>();
        int idx = 0;
        while (idx < responseBody.length()) {
            int start = responseBody.indexOf('{', idx);
            if (start < 0) {
                break;
            }
            int end = findJsonObjectEnd(responseBody, start);
            if (end < 0) {
                break;
            }
            try {
                result.add(objectMapper.readTree(responseBody.substring(start, end + 1)));
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to parse Gmail batch JSON", ex);
            }
            idx = end + 1;
        }
        return result;
    }

    /** 각 batch part에서 첫 JSON object 블록만 추출 */
    private Optional<String> parseJsonFromBatchPart(String part) {
        int jsonStart = part.indexOf('{');
        if (jsonStart < 0) {
            return Optional.empty();
        }
        int jsonEnd = findJsonObjectEnd(part, jsonStart);
        if (jsonEnd < 0) {
            return Optional.empty();
        }
        return Optional.of(part.substring(jsonStart, jsonEnd + 1));
    }

    /** 문자열/escape를 고려해 균형이 맞는 JSON object의 끝 위치를 찾는다. */
    private static int findJsonObjectEnd(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
