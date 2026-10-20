package note_api.mail.ai;

/** 채팅 답변. subject·body가 있을 때만 메일 반영. */
public record MailAiChatResponse(String message, String subject, String body) {}
