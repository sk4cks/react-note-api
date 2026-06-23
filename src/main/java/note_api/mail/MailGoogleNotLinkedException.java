package note_api.mail;

public class MailGoogleNotLinkedException extends RuntimeException {

    public MailGoogleNotLinkedException() {
        super("Google login with Gmail scope is required");
    }
}
