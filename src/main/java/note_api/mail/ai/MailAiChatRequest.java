package note_api.mail.ai;

import java.util.List;

/** 작성 중 메일과 함께 보내는 채팅. */
public record MailAiChatRequest(List<MailAiMessage> messages, String subject, String body) {}
