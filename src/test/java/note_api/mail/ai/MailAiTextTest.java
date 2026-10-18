package note_api.mail.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MailAiTextTest {

    @Test
    void stripsTypedPrefixAndQuotes() {
        assertThat(MailAiText.continuation("회의를", "\"회의를 미루고 싶습니다\"")).isEqualTo(" 미루고 싶습니다");
    }

    @Test
    void keepsContinuationOnly() {
        assertThat(MailAiText.continuation("안녕", "하세요")).isEqualTo("하세요");
    }

    @Test
    void emptyWhenSameAsTyped() {
        assertThat(MailAiText.continuation("안녕", "안녕")).isEmpty();
    }

    @Test
    void plainDropsTags() {
        assertThat(MailAiText.plain("<p>안녕 <b>회의</b></p>")).isEqualTo("안녕 회의");
    }
}
