package note_api.mail.ai;

import note_api.auth.AuthServerClient;
import note_api.contact.dto.ContactResponse;
import note_api.mail.MailService;
import note_api.mail.dto.MailMessageListDto;
import note_api.mail.dto.MailMessageSummaryDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/** 채팅 프롬프트용 최근 메일·주소록. 본문·첨부 제외. */
@Component
public class MailAiContext {

    private static final Logger log = LoggerFactory.getLogger(MailAiContext.class);
    private static final int MAIL_LIMIT = 5;
    private static final int CONTACT_LIMIT = 8;

    private final MailService mailService;
    private final AuthServerClient authServerClient;

    /** 메일 목록과 주소록 조회 주입. */
    public MailAiContext(MailService mailService, AuthServerClient authServerClient) {
        this.mailService = mailService;
        this.authServerClient = authServerClient;
    }

    /** 받은·보낸 메일 5통과 주소 8개. 조회가 실패하면 그 항목만 생략. */
    public String forUser(String userId) {
        return format(mails(userId, "inbox"), mails(userId, "sent"), contacts(userId));
    }

    /** 프롬프트 문단. 보여줄 줄이 없으면 빈 문자열. */
    static String format(
            List<MailMessageSummaryDto> inbox,
            List<MailMessageSummaryDto> sent,
            List<ContactResponse> contacts) {
        StringBuilder sections = new StringBuilder();
        appendMails(sections, "받은 메일", inbox);
        appendMails(sections, "보낸 메일", sent);
        appendContacts(sections, contacts);

        if (sections.isEmpty()) {
            return "";
        }

        return "이 사용자의 최근 메일과 주소록이다. 받는 사람과 호칭에만 참고한다. 없는 사실은 만들지 않는다."
                + sections;
    }

    /** 폴더 최신 5통. 실패하면 빈 목록. */
    private List<MailMessageSummaryDto> mails(String userId, String folder) {
        try {
            MailMessageListDto list = mailService.listMessages(userId, folder, null);

            if (list == null || list.messages() == null) {
                return List.of();
            }

            return list.messages().stream().limit(MAIL_LIMIT).toList();

        } catch (RuntimeException ex) {
            log.warn("ai mail context skipped: {}", folder);

            return List.of();
        }
    }

    /** 주소록 8개. 실패하면 빈 목록. */
    private List<ContactResponse> contacts(String userId) {
        try {
            List<ContactResponse> list = authServerClient.listContacts(userId, null);

            if (list == null) {
                return List.of();
            }

            return list.stream().limit(CONTACT_LIMIT).toList();

        } catch (RuntimeException ex) {
            log.warn("ai contact context skipped");

            return List.of();
        }
    }

    /** 폴더 제목 아래 한 줄. 날짜, 보낸 사람, 받는 사람, 제목. */
    private static void appendMails(StringBuilder out, String title, List<MailMessageSummaryDto> mails) {
        if (mails == null || mails.isEmpty()) {
            return;
        }

        out.append("\n").append(title);

        for (MailMessageSummaryDto mail : mails) {
            out.append("\n- ")
                    .append(clip(mail.date(), 32))
                    .append(" ")
                    .append(person(mail.from(), mail.fromEmail()))
                    .append(" → ")
                    .append(clip(mail.to(), 80))
                    .append(" / ")
                    .append(clip(mail.subject(), 80));
        }
    }

    /** 이름과 메일 주소. */
    private static void appendContacts(StringBuilder out, List<ContactResponse> contacts) {
        if (contacts == null || contacts.isEmpty()) {
            return;
        }

        StringBuilder lines = new StringBuilder();

        for (ContactResponse contact : contacts) {
            // 주소가 없는 연락처는 생략.
            if (contact.email() == null || contact.email().isBlank()) {
                continue;
            }

            lines.append("\n- ").append(person(contact.displayName(), contact.email()));
        }

        if (lines.isEmpty()) {
            return;
        }

        out.append("\n주소록").append(lines);
    }

    /** 표시 이름과 주소. 이름이 없으면 주소만. */
    private static String person(String name, String email) {
        String who = clip(name, 40);
        String address = clip(email, 80);

        if (who.isEmpty()) {
            return address;
        }

        if (address.isEmpty()) {
            return who;
        }

        return who + " <" + address + ">";
    }

    /** 한 줄 길이 제한. */
    private static String clip(String value, int max) {
        if (value == null) {
            return "";
        }

        String text = value.replaceAll("\\s+", " ").trim();

        if (text.length() <= max) {
            return text;
        }

        return text.substring(0, max);
    }
}
