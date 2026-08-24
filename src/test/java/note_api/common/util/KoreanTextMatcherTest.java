package note_api.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KoreanTextMatcherTest {

    @Test
    void matchesIncompleteSyllableAndChosung() {
        assertThat(KoreanTextMatcher.matches("ㄱ", "김철수")).isTrue();
        assertThat(KoreanTextMatcher.matches("기", "김철수")).isTrue();
        assertThat(KoreanTextMatcher.matches("ㄱㅊㅅ", "김철수")).isTrue();
        assertThat(KoreanTextMatcher.matches("김ㅊ", "김철수")).isTrue();
        assertThat(KoreanTextMatcher.matches("ㄱㅅ", "김철수")).isFalse();
    }
}
