package note_api.mail.ai;

import note_api.contact.dto.ContactResponse;
import note_api.mail.dto.MailMessageSummaryDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MailAiContextTest {

    @Test
    void formatsRecentMailAndContactsWithoutBody() {
        MailMessageSummaryDto inbox = new MailMessageSummaryDto(
                "1", "inbox", "김민수", "minsu@note.local", "me@note.local",
                "회의 안내", "본문 미리보기는 넣지 않는다", "10-25 09:00", false);
        ContactResponse contact = new ContactResponse(1L, 2L, "sk6cks", "sk6cks@note.local", false);

        String text = MailAiContext.format(List.of(inbox), List.of(), List.of(contact));

        assertThat(text).contains("받은 메일");
        assertThat(text).contains("김민수 <minsu@note.local>");
        assertThat(text).contains("회의 안내");
        assertThat(text).contains("sk6cks <sk6cks@note.local>");
        assertThat(text).doesNotContain("본문 미리보기");
    }

    @Test
    void emptyWhenNothingToShow() {
        assertThat(MailAiContext.format(List.of(), List.of(), List.of())).isEmpty();
    }
}
