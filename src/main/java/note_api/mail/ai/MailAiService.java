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
            너는 메일 작성을 돕는 대화 상대다. 한국어로 자연스럽게 짧게 답한다.
            질문이면 그 질문에 답한다. 메일 형식으로 바꾸지 않는다.
            메일 작성, 고쳐 쓰기, 제목이나 본문을 부탁할 때만 설명 없이 이 형식만 출력한다.
            제목: 한 줄
            본문: 안녕하세요로 시작해 마침표로 끝나는 완전한 문장
            본문을 지만, 그리고, 그래서, 그러나, 그런데로 시작하지 않는다.
            """;

    private static final String REPAIR_SYSTEM = """
            너는 메일 작성 도우미다. 잘린 답은 버리고 메일을 처음부터 다시 쓴다.
            설명 없이 이 형식만 출력한다.
            제목: 한 줄
            본문: 안녕하세요로 시작하는 완전한 문장. 마침표로 끝낸다.
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
                참고용으로 지금 화면에 있는 메일. 사용자가 메일을 부탁할 때만 이어서 쓴다.
                제목: %s
                본문: %s
                """.formatted(
                tail(MailAiText.plain(request.subject()), 200),
                tail(MailAiText.plain(request.body()), 1500));
        List<MailAiMessage> withDraft = new ArrayList<>();
        withDraft.add(new MailAiMessage("user", context));
        withDraft.addAll(history);

        String asked = asked(history);
        String reply = mailAiClient.chat(CHAT_SYSTEM, withDraft, 400).trim();

        if (!MailAiText.wantsMail(asked)) {
            return new MailAiChatResponse(reply, "", reply);
        }

        if (MailAiText.mailShaped(reply) && !MailAiText.completeBody(MailAiText.bodyOf(reply))) {
            reply = rewrite(asked, reply);
        }

        if (MailAiText.mailShaped(reply) && !MailAiText.completeBody(MailAiText.bodyOf(reply))) {
            return new MailAiChatResponse("메일에 넣을 만큼 완전한 문장이 아닙니다. 다시 보내 주세요.", "", "");
        }

        String subject = MailAiText.subjectOf(reply);
        String body = MailAiText.bodyOf(reply);

        if (subject.isEmpty() && body.isEmpty()) {
            return new MailAiChatResponse(reply, "", reply);
        }

        String message = "제목: " + subject + "\n본문: " + body;

        return new MailAiChatResponse(message, subject, body);
    }

    /** 접속어로 시작한 본문을 인사부터 다시 받기. */
    private String rewrite(String asked, String broken) {
        String prompt = """
                부탁: %s
                버린 답:
                %s
                문장 중간부터 시작했다. 버리고 형식대로 처음부터 다시 써라.
                """.formatted(asked, broken);

        return mailAiClient.chat(REPAIR_SYSTEM, List.of(new MailAiMessage("user", prompt)), 400).trim();
    }

    /** 이번 부탁 문장. */
    private static String asked(List<MailAiMessage> history) {
        if (history.isEmpty()) {
            return "";
        }

        return history.get(history.size() - 1).content();
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
