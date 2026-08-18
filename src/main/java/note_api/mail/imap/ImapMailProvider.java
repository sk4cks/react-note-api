package note_api.mail.imap;

import note_api.auth.AuthServerClient;
import note_api.auth.dto.MailboxCredentialsResponse;
import note_api.mail.MailMimeFactory;
import note_api.mail.MailProvider;
import note_api.mail.dto.MailAttachmentContent;
import note_api.mail.dto.MailFolderDto;
import note_api.mail.dto.MailMessageDetailDto;
import note_api.mail.dto.MailMessageListDto;
import note_api.mail.dto.MailMessageSummaryDto;
import note_api.mail.dto.SendMailRequest;
import note_api.common.exception.ApiException;
import note_api.common.exception.ErrorCode;
import jakarta.mail.Address;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * Mailcow IMAP/SMTP 기반 {@link note_api.mail.MailProvider}.
 * <p>
 * 웹메일 2단계: 자체 {@code userId@도메인} 메일함. BFF가 Gmail API 대신 Jakarta Mail로 접속한다.
 * {@code app.mail.provider=imap} (로컬 기본)일 때 {@link note_api.mail.MailService}가 이 빈을 사용한다.
 * <p>
 * 자격은 Auth 내부 API {@code GET /auth/users/{userId}/mailbox} 로만 조회 (BFF→DB 직접 금지).
 * IMAPS 993 / SMTP STARTTLS 587. 로컬 self-signed·IP 접속은 SSL hostname 검증 비활성.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImapMailProvider implements MailProvider {

    /** 한 페이지에 가져올 메시지 수 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final AuthServerClient authServerClient;

    /**
     * 폴더 메시지 목록을 최신순으로 조회한다.
     * <p>
     * IMAP 번호는 1..N (N=최신). {@code pageToken}은 앞에서 건너뛴 개수(offset) 문자열.
     * 응답 id는 UID — 상세 API·프론트 라우팅 키.
     *
     * @param userId    JWT subject (= SYS_USER.USER_ID)
     * @param folder    SPA 폴더 id ({@code inbox}/{@code sent}/{@code draft} …)
     * @param pageToken offset 문자열. 첫 페이지는 {@code null}
     * @return 요약 목록 + 다음 offset (없으면 null)
     */
    @Override
    public MailMessageListDto listMessages(String userId, String folder, String pageToken) {
        MailboxCredentialsResponse creds = authServerClient.fetchMailboxCredentials(userId);
        int offset = parseOffset(pageToken);
        try (ImapSession session = openImap(creds)) {
            Folder imapFolder = openFolder(session.store(), folder, Folder.READ_ONLY);
            try {
                int total = imapFolder.getMessageCount();
                if (total == 0) {
                    return new MailMessageListDto(List.of(), null);
                }
                // newest first: messages[total] is newest
                int end = total - offset;
                if (end < 1) {
                    return new MailMessageListDto(List.of(), null);
                }
                int start = Math.max(1, end - DEFAULT_PAGE_SIZE + 1);
                Message[] messages = imapFolder.getMessages(start, end);
                UIDFolder uidFolder = (UIDFolder) imapFolder;

                // getMessages 는 오래된→최신 순 → 역순으로 DTO
                List<MailMessageSummaryDto> summaries = new ArrayList<>(messages.length);
                for (int i = messages.length - 1; i >= 0; i--) {
                    summaries.add(toSummary(messages[i], uidFolder, normalizeFolder(folder)));
                }

                int nextOffset = offset + summaries.size();
                String nextPage = nextOffset < total ? String.valueOf(nextOffset) : null;

                return new MailMessageListDto(summaries, nextPage);
            } finally {
                closeQuietly(imapFolder);
            }

        } catch (MessagingException | IOException ex) {
            throw new IllegalStateException("IMAP list failed for user " + userId, ex);
        }
    }

    /**
     * UID로 메시지 상세를 조회한다. unread면 {@code SEEN} 플래그를 켠다.
     * <p>
     * mark-as-read 실패 시에도 본문은 반환 (Gmail 경로와 동일).
     * threadId는 IMAP 스레드가 약해 MVP에서 UID와 같은 값.
     *
     * @param userId    JWT subject
     * @param folder    SPA 폴더 id — UID는 폴더별로 다르므로 반드시 목록과 같은 폴더여야 한다
     * @param messageId IMAP UID 문자열
     * @return 상세 DTO
     */
    @Override
    public MailMessageDetailDto getMessage(String userId, String folder, String messageId) {
        return withImapMessage(userId, folder, messageId, Folder.READ_WRITE, "get", (message, uidFolder) -> {
            MailMessageDetailDto detail = toDetail(message, uidFolder, normalizeFolder(folder));
            if (!detail.unread()) {
                return detail;
            }
            try {
                message.setFlag(Flags.Flag.SEEN, true);

            } catch (MessagingException ex) {
                log.warn("Failed to mark IMAP message as read: {}", messageId, ex);

                return detail;
            }

            return detail.asRead();
        });
    }

    /**
     * 첨부파일 본문을 읽는다. {@code attachmentId}는 상세 응답이 내려준 MIME part 경로.
     *
     * @param userId       JWT subject
     * @param folder       SPA 폴더 id
     * @param messageId    IMAP UID 문자열
     * @param attachmentId MIME part 인덱스 경로 (예: {@code 1}, {@code 0.2})
     */
    @Override
    public MailAttachmentContent getAttachment(
            String userId, String folder, String messageId, String attachmentId) {
        return withImapMessage(
                userId,
                folder,
                messageId,
                Folder.READ_ONLY,
                "attachment fetch",
                (message, uidFolder) -> ImapMimeReader.readAttachment(message, attachmentId));
    }

    @FunctionalInterface
    private interface ImapMessageWork<T> {
        T apply(Message message, UIDFolder uidFolder) throws MessagingException, IOException;
    }

    /** IMAP 폴더를 열고 UID로 메시지를 찾아 작업을 수행한 뒤 폴더를 닫는다. */
    private <T> T withImapMessage(
            String userId, String folder, String messageId, int mode, String action, ImapMessageWork<T> work) {
        MailboxCredentialsResponse creds = authServerClient.fetchMailboxCredentials(userId);
        long uid = parseUid(messageId);
        try (ImapSession session = openImap(creds)) {
            Folder imapFolder = openFolder(session.store(), folder, mode);
            try {
                UIDFolder uidFolder = (UIDFolder) imapFolder;

                return work.apply(requireMessage(uidFolder, uid, messageId), uidFolder);
            } finally {
                closeQuietly(imapFolder);
            }

        } catch (MessagingException | IOException ex) {
            throw new IllegalStateException("IMAP " + action + " failed for user " + userId, ex);
        }
    }

    private static Message requireMessage(UIDFolder uidFolder, long uid, String messageId)
            throws MessagingException {
        Message message = uidFolder.getMessageByUID(uid);
        if (message == null) {
            throw new ApiException(ErrorCode.MAIL_MESSAGE_NOT_FOUND, messageId);
        }

        return message;
    }

    private static long parseUid(String messageId) {
        try {
            return Long.parseLong(messageId);

        } catch (NumberFormatException ex) {
            throw new ApiException(ErrorCode.MAIL_MESSAGE_NOT_FOUND, messageId);
        }
    }

    /**
     * SMTP STARTTLS로 메일을 발송한 뒤, IMAP Sent 폴더에 사본을 APPEND한다.
     * (Mailcow는 SMTP만으로 Sent에 자동 저장하지 않음 — 클라이언트가 저장해야 함)
     *
     * @param userId  JWT subject
     * @param request to / subject / body / attachments
     */
    @Override
    public void sendMessage(String userId, SendMailRequest request) {
        MailboxCredentialsResponse creds = authServerClient.fetchMailboxCredentials(userId);
        try {
            Session smtpSession = createSmtpSession(creds);
            MimeMessage message = MailMimeFactory.create(smtpSession, creds.mailAddress(), request);
            message.setSentDate(new Date());
            Transport.send(message);
            appendToSent(creds, message);

        } catch (MessagingException ex) {
            throw new IllegalStateException("SMTP send failed for user " + userId, ex);
        }
    }

    /**
     * 발송한 MimeMessage를 IMAP Sent에 읽음(\\Seen) 상태로 저장한다.
     */
    private void appendToSent(MailboxCredentialsResponse creds, MimeMessage message)
            throws MessagingException {
        try (ImapSession session = openImap(creds)) {
            Folder sent = openFolder(session.store(), "sent", Folder.READ_WRITE);
            try {
                message.setFlag(Flags.Flag.SEEN, true);
                sent.appendMessages(new Message[] {message});
            } finally {
                closeQuietly(sent);
            }
        }
    }

    /**
     * 폴더 뱃지용 통계.
     * MVP는 INBOX unread만 집계. sent/draft는 0 placeholder (Gmail API 계약 유지).
     *
     * @param userId JWT subject
     * @return inbox / sent / draft 항목
     */
    @Override
    public List<MailFolderDto> getFolderStats(String userId) {
        MailboxCredentialsResponse creds = authServerClient.fetchMailboxCredentials(userId);
        try (ImapSession session = openImap(creds)) {
            Folder inbox = openFolder(session.store(), "inbox", Folder.READ_ONLY);
            try {
                int unread = inbox.getUnreadMessageCount();

                return List.of(
                        new MailFolderDto("inbox", "받은편지함", unread),
                        new MailFolderDto("sent", "보낸편지함", 0),
                        new MailFolderDto("draft", "임시보관함", 0));
            } finally {
                closeQuietly(inbox);
            }

        } catch (MessagingException ex) {
            throw new IllegalStateException("IMAP folder stats failed for user " + userId, ex);
        }
    }

    /**
     * IMAPS Store에 연결한다. 호출부는 try-with-resources로 {@link ImapSession}을 닫는다.
     *
     * @param creds Auth mailbox 자격 (host/port/주소/비번)
     * @return 연결된 Store 래퍼
     */
    private ImapSession openImap(MailboxCredentialsResponse creds) throws MessagingException {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", creds.imapHost());
        props.put("mail.imaps.port", String.valueOf(creds.imapPort()));
        props.put("mail.imaps.ssl.enable", "true");
        props.put("mail.imaps.ssl.trust", "*");
        // Mailcow local self-signed / IP(127.0.0.1) — hostname 검증 끄기
        props.put("mail.imaps.ssl.checkserveridentity", "false");
        Session session = Session.getInstance(props);
        Store store = session.getStore("imaps");
        store.connect(creds.imapHost(), creds.imapPort(), creds.mailAddress(), creds.password());

        return new ImapSession(store);
    }

    /**
     * SMTP STARTTLS용 Session을 만든다. Authenticator에 메일함 주소/비번을 넣는다.
     *
     * @param creds Auth mailbox 자격
     * @return SMTP Session
     */
    private Session createSmtpSession(MailboxCredentialsResponse creds) {
        Properties props = new Properties();
        props.put("mail.smtp.host", creds.smtpHost());
        props.put("mail.smtp.port", String.valueOf(creds.smtpPort()));
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.ssl.trust", "*");
        props.put("mail.smtp.ssl.checkserveridentity", "false");

        return Session.getInstance(props, new jakarta.mail.Authenticator() {
            @Override
            protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
                return new jakarta.mail.PasswordAuthentication(creds.mailAddress(), creds.password());
            }
        });
    }

    /**
     * SPA folder id에 해당하는 IMAP Folder를 연다.
     * Sent/Drafts/Trash가 없으면 생성하고, 그래도 실패하면 예외.
     */
    private static Folder openFolder(Store store, String folder, int mode) throws MessagingException {
        String name = toImapFolderName(folder);
        Folder imapFolder = store.getFolder(name);
        if (!imapFolder.exists()) {
            if ("INBOX".equals(name) || !imapFolder.create(Folder.HOLDS_MESSAGES)) {
                throw new MessagingException("IMAP folder not found: " + name);
            }
        }
        imapFolder.open(mode);

        return imapFolder;
    }

    /**
     * SPA 폴더 id → Mailcow IMAP 폴더명.
     * {@code inbox}→INBOX, {@code sent}→Sent, {@code draft}→Drafts, {@code trash}→Trash.
     */
    private static String toImapFolderName(String folder) {
        return switch (normalizeFolder(folder)) {
            case "sent" -> "Sent";
            case "draft" -> "Drafts";
            case "trash" -> "Trash";
            default -> "INBOX";
        };
    }

    /** 폴더 문자열을 trim + lower-case. 비어 있으면 {@code inbox}. */
    private static String normalizeFolder(String folder) {
        return StringUtils.hasText(folder) ? folder.trim().toLowerCase() : "inbox";
    }

    /**
     * pageToken을 offset 정수로 파싱한다.
     * null/빈 값/비숫자는 0.
     */
    private static int parseOffset(String pageToken) {
        if (!StringUtils.hasText(pageToken)) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(pageToken));

        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /**
     * IMAP Message → 목록용 요약 DTO.
     * id = UID, unread = SEEN 플래그 없음.
     */
    private static MailMessageSummaryDto toSummary(Message message, UIDFolder uidFolder, String folder)
            throws MessagingException, IOException {
        Envelope envelope = envelope(message, uidFolder);

        return new MailMessageSummaryDto(
                String.valueOf(envelope.uid()),
                folder,
                envelope.fromName(),
                envelope.fromEmail(),
                envelope.subject(),
                previewOf(ImapMimeReader.previewText(message)),
                formatDate(envelope.sentDate()),
                envelope.unread());
    }

    /**
     * IMAP Message → 상세 DTO.
     * threadId는 MVP에서 UID와 동일. 본문은 HTML 우선, 인라인 이미지는 data URL로 치환된다.
     */
    private static MailMessageDetailDto toDetail(Message message, UIDFolder uidFolder, String folder)
            throws MessagingException, IOException {
        Envelope envelope = envelope(message, uidFolder);
        ImapMimeReader.Content content = ImapMimeReader.read(message);

        return new MailMessageDetailDto(
                String.valueOf(envelope.uid()),
                String.valueOf(envelope.uid()),
                folder,
                envelope.fromName(),
                envelope.fromEmail(),
                envelope.to(),
                envelope.subject(),
                previewOf(content.preview()),
                content.body(),
                content.bodyContentType(),
                formatDate(envelope.sentDate()),
                envelope.unread(),
                content.attachments());
    }

    private record Envelope(
            long uid, String fromEmail, String fromName, String to, String subject, Date sentDate, boolean unread) {}

    private static Envelope envelope(Message message, UIDFolder uidFolder) throws MessagingException {
        Address[] from = message.getFrom();
        String fromEmail = extractEmail(from);

        return new Envelope(
                uidFolder.getUID(message),
                fromEmail,
                extractPersonal(from, fromEmail),
                extractEmail(message.getRecipients(Message.RecipientType.TO)),
                message.getSubject() != null ? message.getSubject() : "(no subject)",
                message.getSentDate(),
                !message.isSet(Flags.Flag.SEEN));
    }

    /** From/To 주소 배열에서 첫 번째 이메일 주소 문자열을 꺼낸다. */
    private static String extractEmail(Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return "";
        }
        if (addresses[0] instanceof InternetAddress internetAddress) {
            return internetAddress.getAddress() != null ? internetAddress.getAddress() : "";
        }

        return addresses[0].toString();
    }

    /**
     * 표시 이름(personal)을 꺼낸다.
     * personal이 없으면 이메일 주소, 그것도 없으면 fallback.
     */
    private static String extractPersonal(Address[] addresses, String fallback) {
        if (addresses == null || addresses.length == 0) {
            return fallback;
        }
        if (addresses[0] instanceof InternetAddress internetAddress) {
            if (StringUtils.hasText(internetAddress.getPersonal())) {
                return internetAddress.getPersonal();
            }

            return internetAddress.getAddress() != null ? internetAddress.getAddress() : fallback;
        }

        return addresses[0].toString();
    }

    /** 목록 preview용. 공백 압축 후 최대 120자. */
    private static String previewOf(String body) {
        if (!StringUtils.hasText(body)) {
            return "";
        }
        String compact = body.replaceAll("\\s+", " ").trim();

        return compact.length() <= 120 ? compact : compact.substring(0, 120);
    }

    /** Date → ISO-8601 offset datetime. null이면 현재 시각. */
    private static String formatDate(Date date) {
        if (date == null) {
            return Instant.now().atOffset(ZoneOffset.UTC).format(ISO);
        }

        return date.toInstant().atOffset(ZoneOffset.UTC).format(ISO);
    }

    /** 열린 Folder를 조용히 닫는다. 예외는 무시. */
    private static void closeQuietly(Folder folder) {
        if (folder != null && folder.isOpen()) {
            try {
                folder.close(false);

            } catch (MessagingException ignored) {
                // ignore
            }
        }
    }

    /**
     * IMAP {@link Store}를 try-with-resources로 닫기 위한 래퍼.
     * {@link #openImap}이 반환하고, list/get/stats에서 사용한다.
     */
    private static final class ImapSession implements AutoCloseable {
        private final Store store;

        private ImapSession(Store store) {
            this.store = store;
        }

        /** 연결된 Store (폴더 open용). */
        private Store store() {
            return store;
        }

        /** Store disconnect. 예외 무시. */
        @Override
        public void close() {
            try {
                if (store != null && store.isConnected()) {
                    store.close();
                }

            } catch (MessagingException ignored) {
                // ignore
            }
        }
    }
}
