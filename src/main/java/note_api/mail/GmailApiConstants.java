package note_api.mail;

import java.util.regex.Pattern;

final class GmailApiConstants {

    private GmailApiConstants() {}

    static final String BATCH_URL = "https://www.googleapis.com/batch/gmail/v1";
    static final String USERS_ME_BASE = "https://gmail.googleapis.com/gmail/v1/users/me";

    static final String METADATA_QUERY =
            "format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date";

    static final int DEFAULT_LIST_MAX_RESULTS = 15;

    static final String FOLDER_INBOX = "inbox";
    static final String FOLDER_SENT = "sent";
    static final String FOLDER_DRAFT = "draft";

    static final String LABEL_INBOX = "INBOX";
    static final String LABEL_SENT = "SENT";
    static final String LABEL_DRAFT = "DRAFT";
    static final String LABEL_UNREAD = "UNREAD";

    static final String HEADER_FROM = "from";
    static final String HEADER_TO = "to";
    static final String HEADER_SUBJECT = "subject";
    static final String HEADER_DATE = "date";

    static final String MIME_TEXT_PLAIN = "text/plain";
    static final String MIME_TEXT_HTML = "text/html";

    static final Pattern FROM_HEADER = Pattern.compile(
            "^(?:\"?([^\"<]*)\"?\\s*)?<([^>]+)>$|^(\\S+@\\S+)$");
    static final Pattern BATCH_BOUNDARY = Pattern.compile("boundary=([^;\\s]+)");
}
