package note_api.mail.ai;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 모델 답에서 이어쓰기, 수정 부탁, 제목·본문 추출. */
final class MailAiText {

    /** 줄 맨 앞의 제목 라벨. */
    private static final Pattern SUBJECT_LINE = Pattern.compile("(?m)^제목\\s*[:：]\\s*(.+)$");

    /** 본문 라벨. 마지막 라벨 뒤를 본문으로 사용. */
    private static final Pattern BODY_LABEL = Pattern.compile("본문\\s*[:：]\\s*");

    /** 본문 뒤에 다시 나온 제목 라벨. 그 앞까지만 본문. */
    private static final Pattern NEXT_SUBJECT = Pattern.compile("(?m)^제목\\s*[:：]");

    /** 접속어로 시작하는 문장. */
    private static final Pattern DANGLING = Pattern.compile("^(지만|그리고|그래서|그러나|그런데|또한|그러면|그래도|하면서|니까|며)(\\s|,|\\.)");

    /** 한 글자만 앞에 남은 시작. */
    private static final Pattern ONE_SYLLABLE = Pattern.compile("^[가-힣](\\s|,)");

    /** 본문 안에 남은 제목·본문 라벨. */
    private static final Pattern EMBEDDED_LABEL = Pattern.compile("제목\\s*[:：]|본문\\s*[:：]");

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

    /** 화면 메일 수정 부탁. 새 메일 작성보다 먼저 판별. */
    static boolean wantsEdit(String text) {
        return hasWord(text, "지워", "들어줘", "빼줘", "고쳐", "다듬", "정리", "수정");
    }

    /** 새 메일 초안 부탁. 질문 제외. */
    static boolean wantsMail(String text) {
        return hasWord(text, "메일", "제목", "본문", "써줘", "작성");
    }

    /** 공백을 빼고 단어 포함 여부 판별. */
    private static boolean hasWord(String text, String... words) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String compact = text.replace(" ", "");

        for (String word : words) {
            if (compact.contains(word)) {
                return true;
            }
        }

        return false;
    }

    /** 제목·본문 형식이 있는 답. */
    static boolean mailShaped(String raw) {
        return raw != null && raw.contains("제목") && raw.contains("본문");
    }

    /** 마지막 제목 줄 추출. 없으면 빈 문자열. */
    static String subjectOf(String raw) {
        if (raw == null) {
            return "";
        }

        Matcher matcher = SUBJECT_LINE.matcher(raw);
        String last = "";

        // 제목이 여러 줄이면 마지막 비어 있지 않은 줄 유지.
        while (matcher.find()) {
            String line = matcher.group(1).trim();
            int bodyLabel = line.indexOf("본문");

            // 같은 줄에 본문 라벨이 붙으면 그 앞만 제목.
            if (bodyLabel >= 0) {
                line = line.substring(0, bodyLabel).replaceAll("[:：]\\s*$", "").trim();
            }

            if (!line.isEmpty()) {
                last = line;
            }
        }

        return last;
    }

    /** 마지막 본문 라벨 뒤 추출. 없으면 빈 문자열. */
    static String bodyOf(String raw) {
        if (raw == null) {
            return "";
        }

        Matcher labels = BODY_LABEL.matcher(raw);
        int end = -1;

        // 본문 라벨이 여러 번이면 마지막 위치 사용.
        while (labels.find()) {
            end = labels.end();
        }

        if (end < 0) {
            return "";
        }

        String rest = raw.substring(end).trim();
        Matcher nextSubject = NEXT_SUBJECT.matcher(rest);

        // 그 다음 줄의 제목 라벨은 본문에서 제외.
        if (nextSubject.find()) {
            rest = rest.substring(0, nextSubject.start()).trim();
        }

        return rest;
    }

    /** 본문에 넣을 문장 판별. 짧은 문장, 남은 라벨, 접속어 시작은 거절. */
    static boolean usableBody(String body) {
        if (body == null) {
            return false;
        }

        String text = body.trim();

        if (text.length() < 8
                || EMBEDDED_LABEL.matcher(text).find()
                || DANGLING.matcher(text).find()
                || ONE_SYLLABLE.matcher(text).find()) {
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

    /** 새 메일 본문 판별. 12자 미만이거나 인사가 아니면 거절. */
    static boolean completeBody(String body) {
        if (!usableBody(body)) {
            return false;
        }

        String text = body.trim();

        return text.length() >= 12 && (text.startsWith("안녕") || text.startsWith("반갑"));
    }
}
