package note_api.mail.ai;

/** 제목 또는 본문 커서 뒤 이어쓰기 요청. */
public record MailAiCompleteRequest(String field, String text, String subject, String body) {}
