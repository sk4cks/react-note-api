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

    @Test
    void splitsSubjectAndBody() {
        String raw = "제목: 반갑습니다\n본문: 안녕하세요. 만나서 반갑습니다.";

        assertThat(MailAiText.subjectOf(raw)).isEqualTo("반갑습니다");
        assertThat(MailAiText.bodyOf(raw)).isEqualTo("안녕하세요. 만나서 반갑습니다.");
        assertThat(MailAiText.completeBody(MailAiText.bodyOf(raw))).isTrue();
    }

    @Test
    void wantsMailOnlyForDraftRequests() {
        assertThat(MailAiText.wantsMail("너는 몇살이야?")).isFalse();
        assertThat(MailAiText.wantsMail("sk6cks에게 반갑다는 메일 써줘")).isTrue();
    }

    @Test
    void rejectsBodyStartingMidSentence() {
        assertThat(MailAiText.completeBody("지만, 이번에 만나서 반갑습니다. 알려주시기 바랍니다.")).isFalse();
        assertThat(MailAiText.completeBody("제 오늘도 좋은 하루 보내세요.")).isFalse();
    }
}
