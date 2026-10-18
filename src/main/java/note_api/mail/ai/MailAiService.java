package note_api.mail.ai;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/** 작성 화면 이어쓰기와 옆 채팅. */
@Service
public class MailAiService {

    private static final String COMPLETE_SYSTEM = """
            너는 메일 자동완성이다. 사용자가 이미 친 글 뒤에 이어질 짧은 구절만 출력한다.
            이미 친 글은 반복하지 않는다. 설명, 따옴표, 줄바꿈 없이 한 구절만.
            이어 쓸 말이 없으면 빈 문자열만 출력한다.
            """;

    private static final String CHAT_SYSTEM = """
            너는 메일 작성 도우미다. 한국어로 짧게 답한다.
            문장을 부탁하면 메일 본문에 넣을 문장만 제안한다.
            """;

    private final MailAiClient mailAiClient;

    /** AI 호출 클라이언트 주입. */
    public MailAiService(MailAiClient mailAiClient) {
        this.mailAiClient = mailAiClient;
    }

    /** 제목·본문 커서 뒤 짧은 이어쓰기. 두 글자 미만이면 호출 생략. */
    public MailAiCompleteResponse complete(MailAiCompleteRequest request) {
        String field = "subject".equals(request.field()) ? "제목" : "본문";
        String typed = tail(request.text(), 400);

        if (!StringUtils.hasText(typed) || typed.trim().length() < 2) {
            return new MailAiCompleteResponse("");
        }

        String prompt = """
                필드: %s
                제목: %s
                본문: %s
                지금까지: %s
                """.formatted(
                field,
                tail(MailAiText.plain(request.subject()), 200),
                tail(MailAiText.plain(request.body()), 800),
                typed);

        String raw = mailAiClient.complete(COMPLETE_SYSTEM, prompt, 40);

        return new MailAiCompleteResponse(MailAiText.continuation(typed.trim(), raw));
    }

    /** 작성 중인 제목·본문을 맥락으로 넣은 채팅 답변. */
    public MailAiChatResponse chat(MailAiChatRequest request) {
        List<MailAiMessage> history = latest(request.messages());
        String context = """
                지금 작성 중인 메일.
                제목: %s
                본문: %s
                """.formatted(
                tail(MailAiText.plain(request.subject()), 200),
                tail(MailAiText.plain(request.body()), 1500));
        List<MailAiMessage> withDraft = new ArrayList<>();
        withDraft.add(new MailAiMessage("user", context));
        withDraft.addAll(history);

        String reply = mailAiClient.chat(CHAT_SYSTEM, withDraft, 400).trim();

        return new MailAiChatResponse(reply);
    }

    /** 끝에서 max자. 이어쓰기는 최근 입력만 사용. */
    private static String tail(String value, int max) {
        if (value == null) {
            return "";
        }

        String trimmed = value.trim();

        if (trimmed.length() <= max) {
            return trimmed;
        }

        return trimmed.substring(trimmed.length() - max);
    }

    /** 최근 10개만. 각 내용은 2000자. */
    private static List<MailAiMessage> latest(List<MailAiMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        int from = Math.max(0, messages.size() - 10);
        List<MailAiMessage> out = new ArrayList<>();

        for (MailAiMessage message : messages.subList(from, messages.size())) {
            if (message == null || !StringUtils.hasText(message.content())) {
                continue;
            }

            String content = message.content().trim();

            if (content.length() > 2000) {
                content = content.substring(0, 2000);
            }

            out.add(new MailAiMessage(message.role(), content));
        }

        return out;
    }
}
