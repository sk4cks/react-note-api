package note_api.mail;

/** Auth에 메일함 비밀번호가 없거나 사용자 없음 (IMAP 경로). */
public class MailMailboxNotFoundException extends RuntimeException {

    public MailMailboxNotFoundException(String userId) {
        super("Mailbox credentials not found for user: " + userId);
    }
}
