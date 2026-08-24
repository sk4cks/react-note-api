package note_api.common.util;

import org.springframework.util.StringUtils;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 한글 부분 입력·초성 검색. ㄱ / 기 / 김 / ㄱㅊㅅ 모두 김철수를 찾는다.
 * Auth {@code KoreanTextMatcher} 와 동일하게 유지한다.
 */
public final class KoreanTextMatcher {

    private static final int SYLLABLE_BASE = 0xAC00;
    private static final int SYLLABLE_LAST = 0xD7A3;
    private static final int CHO_JAMO = 0x1100;
    private static final int JUNG_JAMO = 0x1161;
    private static final int JONG_JAMO = 0x11A8;
    private static final String CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
    private static final String[] JUNG_EXPAND = {
        "ㅏ", "ㅐ", "ㅑ", "ㅒ", "ㅓ", "ㅔ", "ㅕ", "ㅖ",
        "ㅗ", "ㅗㅏ", "ㅗㅐ", "ㅗㅣ", "ㅛ",
        "ㅜ", "ㅜㅓ", "ㅜㅔ", "ㅜㅣ", "ㅠ",
        "ㅡ", "ㅡㅣ", "ㅣ"
    };
    private static final String[] JONG_EXPAND = {
        "",
        "ㄱ", "ㄲ", "ㄱㅅ", "ㄴ", "ㄴㅈ", "ㄴㅎ", "ㄷ",
        "ㄹ", "ㄹㄱ", "ㄹㅁ", "ㄹㅂ", "ㄹㅅ", "ㄹㅌ", "ㄹㅍ", "ㄹㅎ",
        "ㅁ", "ㅂ", "ㅂㅅ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"
    };
    private static final String[] COMPAT_CONSONANT = {
        "ㄱ", "ㄲ", "ㄱㅅ", "ㄴ", "ㄴㅈ", "ㄴㅎ", "ㄷ", "ㄸ", "ㄹ",
        "ㄹㄱ", "ㄹㅁ", "ㄹㅂ", "ㄹㅅ", "ㄹㅌ", "ㄹㅍ", "ㄹㅎ",
        "ㅁ", "ㅂ", "ㅃ", "ㅂㅅ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅉ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"
    };

    private KoreanTextMatcher() {}

    public static boolean matches(String query, String... fields) {
        String needle = normalize(query);
        if (needle.isEmpty()) {
            return true;
        }
        for (String field : fields) {
            if (field != null && contains(needle, normalize(field))) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
    }

    private static boolean contains(String query, String hay) {
        if (hay.contains(query)) {
            return true;
        }
        List<String> hayJamo = jamoChars(hay);
        List<String> queryJamo = jamoChars(query);
        for (int start = 0; start < hayJamo.size(); start++) {
            if (matchFrom(queryJamo, hayJamo, start)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchFrom(List<String> query, List<String> hay, int start) {
        int qi = 0;
        int hi = start;
        while (qi < query.size()) {
            if (hi >= hay.size() || !hay.get(hi).startsWith(query.get(qi))) {
                return false;
            }
            qi += 1;
            hi += 1;
        }
        return true;
    }

    private static List<String> jamoChars(String text) {
        List<String> chars = new ArrayList<>();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            chars.add(toJamo(cp));
            i += Character.charCount(cp);
        }
        return chars;
    }

    private static String toJamo(int cp) {
        if (cp >= SYLLABLE_BASE && cp <= SYLLABLE_LAST) {
            int s = cp - SYLLABLE_BASE;
            int cho = s / 588;
            int jung = (s % 588) / 28;
            int jong = s % 28;
            return CHO.charAt(cho) + JUNG_EXPAND[jung] + JONG_EXPAND[jong];
        }
        if (cp >= 0x3131 && cp <= 0x314E) {
            return COMPAT_CONSONANT[cp - 0x3131];
        }
        if (cp >= 0x314F && cp <= 0x3163) {
            return JUNG_EXPAND[cp - 0x314F];
        }
        if (cp >= CHO_JAMO && cp <= CHO_JAMO + 18) {
            return String.valueOf(CHO.charAt(cp - CHO_JAMO));
        }
        if (cp >= JUNG_JAMO && cp <= JUNG_JAMO + 20) {
            return JUNG_EXPAND[cp - JUNG_JAMO];
        }
        if (cp >= JONG_JAMO && cp <= JONG_JAMO + 26) {
            return JONG_EXPAND[cp - JONG_JAMO + 1];
        }
        return Character.toString(cp);
    }
}
