package note_api.mail.ai;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 모델이 돌려준 문장에서 사용자가 이미 친 부분을 빼는 정리. */
final class MailAiText {

    private static final Pattern SUBJECT_LINE = Pattern.compile("(?m)^제목\\s*[:：]\\s*(.+)$");
    private static final Pattern DANGLING = Pattern.compile("^(지만|그리고|그래서|그러나|그런데|또한|그러면|그래도|하면서|니까|며)(\\s|,|\\.)");
    private static final Pattern ONE_SYLLABLE = Pattern.compile("^[가-힣](\\s|,)");

    private MailAiText() {}

    /** 이미 친 글 뒤에 붙일 짧은 이어쓰기. 없거나 같으면 빈 문자열. */
    static String continuation(String typed, String raw) {
        if (raw == null) {
            return "";
        }

        String text = raw.trim();

        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1).trim();
        }

        if (typed != null && !typed.isBlank() && text.startsWith(typed)) {
            text = text.substring(typed.length());
        }

        int newline = text.indexOf('\n');

        if (newline >= 0) {
            text = text.substring(0, newline);
        }

        boolean spaced = !text.isEmpty() && Character.isWhitespace(text.charAt(0));
        text = text.trim();

        if (spaced && !text.isEmpty()) {
            text = " " + text;
        }

        if (text.length() > 80) {
            text = text.substring(0, 80).trim();
        }

        return text;
    }

    /** HTML 태그를 공백으로 바꿔 모델에 넘길 본문. */
    static String plain(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }

        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    /** 메일 초안을 부탁한 말. 질문에는 해당하지 않음. */
    static boolean wantsMail(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        return text.contains("메일")
                || text.contains("제목")
                || text.contains("본문")
                || text.contains("써줘")
                || text.contains("써 줘")
                || text.contains("작성");
    }

    /** 제목·본문 형식이 있는 답. */
    static boolean mailShaped(String raw) {
        return raw != null && raw.contains("제목") && raw.contains("본문");
    }

    /** 제목 줄. 없으면 빈 문자열. */
    static String subjectOf(String raw) {
        if (raw == null) {
            return "";
        }

        Matcher matcher = SUBJECT_LINE.matcher(raw);

        if (!matcher.find()) {
            return "";
        }

        String line = matcher.group(1).trim();
        int bodyLabel = line.indexOf("본문");

        if (bodyLabel >= 0) {
            line = line.substring(0, bodyLabel).replaceAll("[:：]\\s*$", "").trim();
        }

        return line;
    }

    /** 본문 줄 이후. 없으면 빈 문자열. */
    static String bodyOf(String raw) {
        if (raw == null) {
            return "";
        }

        int label = raw.indexOf("본문");

        if (label < 0) {
            return "";
        }

        return raw.substring(label).replaceFirst("^본문\\s*[:：]\\s*", "").trim();
    }

    /** 인사로 시작해 문장 부호로 끝나는 본문. 접속어로 시작하면 거절. */
    static boolean completeBody(String body) {
        if (body == null) {
            return false;
        }

        String text = body.trim();

        if (text.length() < 12 || DANGLING.matcher(text).find() || ONE_SYLLABLE.matcher(text).find()) {
            return false;
        }

        if (!text.startsWith("안녕") && !text.startsWith("반갑")) {
            return false;
        }

        return text.endsWith("다.")
                || text.endsWith("요.")
                || text.endsWith("까?")
                || text.endsWith("니까?")
                || text.endsWith("니다.")
                || text.endsWith("세요.")
                || text.endsWith("습니까?");
    }
}
