package note_api.mail.ai;

/** 작성 채팅의 한 말풍선. role은 user 또는 assistant. */
public record MailAiMessage(String role, String content) {}
