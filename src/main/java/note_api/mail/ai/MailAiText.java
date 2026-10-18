package note_api.mail.ai;

/** 모델이 돌려준 문장에서 사용자가 이미 친 부분을 빼는 정리. */
final class MailAiText {

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
}
