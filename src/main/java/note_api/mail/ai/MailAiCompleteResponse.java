package note_api.mail.ai;

/** 이미 친 글 뒤에 붙일 회색 추천. 없으면 빈 문자열. */
public record MailAiCompleteResponse(String suggestion) {}
