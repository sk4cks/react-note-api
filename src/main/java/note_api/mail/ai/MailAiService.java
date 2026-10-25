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

    private static final String EDIT_SYSTEM = """
            너는 지금 화면에 있는 메일만 고친다. 사용자가 지우라고 한 내용만 뺀다.
            없는 내용, 계정, 명령어, 비밀번호, 리눅스 설명을 만들지 않는다.
            설명 없이 이 형식만 한 번 출력한다.
            제목: 한 줄
            본문: 고친 본문. 본문 안에 제목: 이나 본문: 을 다시 쓰지 않는다.
            """;

    private static final String REPAIR_SYSTEM = """
            너는 메일 작성 도우미다. 잘린 답은 버리고 메일을 처음부터 다시 쓴다.
            설명 없이 이 형식만 출력한다.
            제목: 한 줄
            본문: 안녕하세요로 시작하는 완전한 문장. 마침표로 끝낸다.
            """;

    private static final int CHAT_TOKENS = 400;
    private static final double CHAT_TEMPERATURE = 0.5;
    private static final double REPAIR_TEMPERATURE = 0.2;

    private final MailAiClient mailAiClient;
    private final MailAiContext mailAiContext;

    /** AI 호출과 사용자 메일·주소록 참고 주입. */
    public MailAiService(MailAiClient mailAiClient, MailAiContext mailAiContext) {
        this.mailAiClient = mailAiClient;
        this.mailAiContext = mailAiContext;
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

    /** 로그인 사용자의 최근 메일·주소록과 화면 메일을 넣고 채팅 답변. */
    public MailAiChatResponse chat(String userId, MailAiChatRequest request) {
        List<MailAiMessage> history = latest(request.messages());
        String asked = asked(history);
        boolean edit = MailAiText.wantsEdit(asked);
        List<MailAiMessage> withDraft = new ArrayList<>();
        String reference = mailAiContext.forUser(userId);

        // 최근 메일·주소록이 있을 때만 대화 앞에 참고 문단 추가.
        if (StringUtils.hasText(reference)) {
            withDraft.add(new MailAiMessage("user", reference));
        }

        withDraft.add(new MailAiMessage("user", screenMail(request, edit)));
        withDraft.addAll(history);
        String reply = mailAiClient.chat(
                edit ? EDIT_SYSTEM : CHAT_SYSTEM,
                withDraft,
                CHAT_TOKENS,
                edit ? REPAIR_TEMPERATURE : CHAT_TEMPERATURE).trim();

        // 고치기 부탁은 화면 메일만 수정.
        if (edit) {
            return edited(asked, request, reply);
        }

        // 질문이 메일 형식으로 오면 넣기 값 없이 문장만 반환.
        if (!MailAiText.wantsMail(asked)) {
            if (MailAiText.mailShaped(reply)) {
                return new MailAiChatResponse(reply, "", "");
            }

            return new MailAiChatResponse(reply, "", reply);
        }

        // 새 메일은 인사로 시작하는 본문만 통과. 한 번 재작성.
        if (!MailAiText.completeBody(MailAiText.bodyOf(reply))) {
            reply = rewrite(asked, reply);
        }

        if (!MailAiText.completeBody(MailAiText.bodyOf(reply))) {
            return new MailAiChatResponse("메일에 넣을 만큼 완전한 문장이 아닙니다. 다시 보내 주세요.", "", "");
        }

        return draftResponse(reply, MailAiText.plain(request.subject()));
    }

    /** 화면에 있는 제목·본문. 고치기면 수정 대상, 아니면 참고. */
    private static String screenMail(MailAiChatRequest request, boolean edit) {
        String lead = edit
                ? "고칠 메일."
                : "참고용으로 지금 화면에 있는 메일. 사용자가 메일을 부탁할 때만 이어서 쓴다.";

        return """
                %s
                제목: %s
                본문: %s
                """.formatted(
                lead,
                tail(MailAiText.plain(request.subject()), 200),
                tail(MailAiText.plain(request.body()), 1500));
    }

    /** 화면 메일 수정 결과. 본문이 짧거나 라벨이 남으면 넣기 값 생략. */
    private MailAiChatResponse edited(String asked, MailAiChatRequest request, String reply) {
        // 한 번 실패하면 화면 메일만 다시 수정.
        if (!MailAiText.usableBody(MailAiText.bodyOf(reply))) {
            reply = rewriteEdit(asked, request, reply);
        }

        if (!MailAiText.usableBody(MailAiText.bodyOf(reply))) {
            return new MailAiChatResponse("지금 메일을 깔끔히 고치지 못했습니다. 다시 부탁해 주세요.", "", "");
        }

        return draftResponse(reply, MailAiText.plain(request.subject()));
    }

    /** 제목·본문 파싱 후 말풍선과 넣기 값 생성. 제목이 없으면 화면 제목 유지. */
    private static MailAiChatResponse draftResponse(String raw, String currentSubject) {
        String subject = MailAiText.subjectOf(raw);
        String body = MailAiText.bodyOf(raw);

        if (subject.isEmpty()) {
            subject = currentSubject == null ? "" : currentSubject;
        }

        String message = "제목: " + subject + "\n본문: " + body;

        return new MailAiChatResponse(message, subject, body);
    }

    /** 엉킨 수정 답 재작성. */
    private String rewriteEdit(String asked, MailAiChatRequest request, String broken) {
        String prompt = screenMail(request, true) + """
                부탁: %s
                버린 답:
                %s
                시킨 내용만 빼고 형식대로 다시 써라. 없는 내용은 만들지 마라.
                """.formatted(asked, broken);

        return mailAiClient.chat(EDIT_SYSTEM, List.of(new MailAiMessage("user", prompt)), CHAT_TOKENS, REPAIR_TEMPERATURE).trim();
    }

    /** 잘린 새 메일 본문 재작성. */
    private String rewrite(String asked, String broken) {
        String prompt = """
                부탁: %s
                버린 답:
                %s
                문장 중간부터 시작했다. 버리고 형식대로 처음부터 다시 써라.
                """.formatted(asked, broken);

        return mailAiClient.chat(REPAIR_SYSTEM, List.of(new MailAiMessage("user", prompt)), CHAT_TOKENS, REPAIR_TEMPERATURE).trim();
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
