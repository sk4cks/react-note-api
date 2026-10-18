package note_api.mail.ai;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 메일 작성 AI. 로그인 필요. */
@RestController
@RequestMapping("/api/mail/ai")
public class MailAiController {

    private final MailAiService mailAiService;

    /** 작성 AI 서비스 주입. */
    public MailAiController(MailAiService mailAiService) {
        this.mailAiService = mailAiService;
    }

    /** 제목·본문 입력 중 커서 뒤 이어쓰기. */
    @PostMapping("/complete")
    public MailAiCompleteResponse complete(
            @AuthenticationPrincipal Jwt jwt, @RequestBody MailAiCompleteRequest request) {
        requireLogin(jwt);

        return mailAiService.complete(request);
    }

    /** 작성 화면 옆 채팅. */
    @PostMapping("/chat")
    public MailAiChatResponse chat(
            @AuthenticationPrincipal Jwt jwt, @RequestBody MailAiChatRequest request) {
        requireLogin(jwt);

        return mailAiService.chat(request);
    }

    /** 인증된 요청만. */
    private static void requireLogin(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new IllegalStateException("login required");
        }
    }
}
