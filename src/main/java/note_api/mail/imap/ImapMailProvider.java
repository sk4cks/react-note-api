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
import note_api.mail.dto.MailRecipientSuggestion;
import note_api.mail.dto.SaveDraftRequest;
import note_api.mail.dto.SendMailRequest;
import note_api.common.exception.ApiException;
import note_api.common.exception.ErrorCode;
import note_api.common.util.KoreanTextMatcher;
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
import org.eclipse.angus.mail.imap.AppendUID;
import org.eclipse.angus.mail.imap.IMAPFolder;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Mailcow IMAP/SMTP 기반 {@link note_api.mail.MailProvider}.
 * <p>
 * 웹메일 2단계: 자체 {@code userId@도메인} 메일함. BFF가 Gmail API 대신 Jakarta Mail로 접속.
 * {@code app.mail.provider=imap} (로컬·k8s 기본)일 때 {@link note_api.mail.MailService}가 쓰는 빈.
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

    /** 휴지통으로 옮기기 전 폴더. IMAP 사용자 플래그. */
    private static final String ORIGIN_INBOX = "NoteOriginInbox";
    private static final String ORIGIN_SENT = "NoteOriginSent";
    private static final String ORIGIN_DRAFT = "NoteOriginDraft";

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final AuthServerClient authServerClient;

    /**
     * 폴더 메시지 목록 최신순 조회.
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
        // Auth 내부 API로 메일함 자격 조회. pageToken은 건너뛸 개수.
        MailboxCredentialsResponse creds = authServerClient.fetchMailboxCredentials(userId);
        int offset = parseOffset(pageToken);

        try (ImapSession session = openImap(creds)) {
            Folder imapFolder = openFolder(session.store(), folder, Folder.READ_ONLY);

            try {
                int total = imapFolder.getMessageCount();

                if (total == 0) {
                    return new MailMessageListDto(List.of(), null);
                }

                // IMAP 번호는 1..N, N이 최신. offset만큼 최신 쪽을 건너뛰는 구간.
                int end = total - offset;

                if (end < 1) {
                    return new MailMessageListDto(List.of(), null);
                }

                int start = Math.max(1, end - DEFAULT_PAGE_SIZE + 1);
                Message[] messages = imapFolder.getMessages(start, end);
                UIDFolder uidFolder = (UIDFolder) imapFolder;

                // getMessages는 오래된→최신. 화면은 최신이 위라 뒤집는 정렬.
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
     * UID로 메시지 상세 조회. unread면 {@code SEEN} 플래그 설정.
     * <p>
     * mark-as-read 실패 시에도 본문은 반환 (Gmail 경로와 동일).
     * threadId는 IMAP 스레드가 약해 MVP에서 UID와 같은 값.
     *
     * @param userId    JWT subject
     * @param folder    SPA 폴더 id. UID는 폴더별로 달라 목록과 같은 폴더가 필요한 조건
     * @param messageId IMAP UID 문자열
     * @return 상세 DTO
     */
    @Override
    public MailMessageDetailDto getMessage(String userId, String folder, String messageId) {
        return withImapMessage(userId, folder, messageId, Folder.READ_WRITE, "get", (message, uidFolder) -> {
            // 본문·첨부 조립.
            MailMessageDetailDto detail = toDetail(message, uidFolder, normalizeFolder(folder));

            if (!detail.unread()) {
                return detail;
            }

            try {
                // 안 읽음이면 SEEN. 실패해도 본문은 반환.
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

    /** IMAP 폴더를 열고 UID로 메시지를 찾아 작업을 수행한 뒤 폴더를 닫는 공통 흐름. */
    private <T> T withImapMessage(
            String userId, String folder, String messageId, int mode, String action, ImapMessageWork<T> work) {
        // 메일함 자격과 UID.
        MailboxCredentialsResponse creds = authServerClient.fetchMailboxCredentials(userId);
        long uid = parseUid(messageId);
        try (ImapSession session = openImap(creds)) {
            Folder imapFolder = openFolder(session.store(), folder, mode);

            try {
                UIDFolder uidFolder = (UIDFolder) imapFolder;

                // 메시지에 작업 후 finally에서 폴더 닫기.
                return work.apply(requireMessage(uidFolder, uid, messageId), uidFolder);

            } finally {
                closeQuietly(imapFolder);
            }

        } catch (MessagingException | IOException ex) {
            throw new IllegalStateException("IMAP " + action + " failed for user " + userId, ex);
        }
    }

    /** UID로 메시지를 찾고, 없으면 MAIL_MESSAGE_NOT_FOUND. */
    private static Message requireMessage(UIDFolder uidFolder, long uid, String messageId)
            throws MessagingException {
        // UID가 없으면 없는 메일.
        Message message = uidFolder.getMessageByUID(uid);
        if (message == null) {
            throw new ApiException(ErrorCode.MAIL_MESSAGE_NOT_FOUND, messageId);
        }

        return message;
    }

    /** 메시지 id 문자열을 IMAP UID로 파싱. 숫자가 아니면 없는 메일. */
    private static long parseUid(String messageId) {
        try {
            return Long.parseLong(messageId);

        } catch (NumberFormatException ex) {
            throw new ApiException(ErrorCode.MAIL_MESSAGE_NOT_FOUND, messageId);
        }
    }

    /**
     * SMTP STARTTLS 발송 뒤, IMAP Sent 폴더에 사본 APPEND.
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
            Date sentDate = new Date();
            message.setSentDate(sentDate);
            // SMTP 발송.
            Transport.send(message);

            // Transport.send는 Bcc 헤더를 제거. Sent 보관본은 다시 만들어 보낸 사람이 보이게 하는 저장.
            MimeMessage sentCopy = MailMimeFactory.create(smtpSession, creds.mailAddress(), request);
            sentCopy.setSentDate(sentDate);
            appendToSent(creds, sentCopy);

            // 초안에서 보냈으면 그 UID 삭제.
            if (StringUtils.hasText(request.draftId())) {
                deleteDraftQuietly(creds, request.draftId());
            }

        } catch (MessagingException ex) {
            throw new IllegalStateException("SMTP send failed for user " + userId, ex);
        }
    }

    /**
     * 발송한 MimeMessage를 IMAP Sent에 읽음(\\Seen) 상태로 저장.
     */
    private void appendToSent(MailboxCredentialsResponse creds, MimeMessage message)
            throws MessagingException {
        try (ImapSession session = openImap(creds)) {
            Folder sent = openFolder(session.store(), "sent", Folder.READ_WRITE);

            try {
                // 읽음으로 표시한 뒤 Sent에 APPEND.
                message.setFlag(Flags.Flag.SEEN, true);
                sent.appendMessages(new Message[] {message});

            } finally {
                closeQuietly(sent);
            }
        }
    }

    /**
     * Drafts에 MIME APPEND. 기존 id가 있으면 새 사본을 넣은 뒤 옛 UID 삭제.
     */
    @Override
    public String saveDraft(String userId, SaveDraftRequest request) {
        MailboxCredentialsResponse creds = authServerClient.fetchMailboxCredentials(userId);

        try {
            Session smtpSession = createSmtpSession(creds);
            // 초안 MIME. DRAFT·읽음 표시 후 APPEND.
            MimeMessage message = MailMimeFactory.create(smtpSession, creds.mailAddress(), request.toSendRequest());
            message.setSentDate(new Date());
            message.setFlag(Flags.Flag.DRAFT, true);
            message.setFlag(Flags.Flag.SEEN, true);

            return appendDraft(creds, message, request.id());

        } catch (MessagingException ex) {
            throw new IllegalStateException("IMAP draft save failed for user " + userId, ex);
        }
    }

    /**
     * 휴지통이면 UID 삭제, 그 외 폴더는 Trash로 이동.
     */
    @Override
    public void deleteMessages(String userId, String folder, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }

        MailboxCredentialsResponse creds = authServerClient.fetchMailboxCredentials(userId);
        String normalized = normalizeFolder(folder);

        try (ImapSession session = openImap(creds)) {
            // 휴지통은 완전 삭제, 나머지는 Trash 이동.
            if ("trash".equals(normalized)) {
                expungeByUid(session.store(), "trash", ids);

                return;
            }

            moveToTrash(session.store(), normalized, ids);

        } catch (MessagingException ex) {
            throw new IllegalStateException("IMAP delete failed for user " + userId, ex);
        }
    }

    /** 휴지통 메일을 들어오기 전 폴더로 되돌린다. 표시가 없으면 받은편지함. */
    @Override
    public void restoreMessages(String userId, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }

        MailboxCredentialsResponse creds = authServerClient.fetchMailboxCredentials(userId);

        try (ImapSession session = openImap(creds)) {
            restoreFromTrash(session.store(), ids);

        } catch (MessagingException ex) {
            throw new IllegalStateException("IMAP restore failed for user " + userId, ex);
        }
    }

    /**
     * 폴더 뱃지용 통계. inbox는 안 읽은 수, sent/draft/trash는 전체 건수.
     */
    @Override
    public List<MailFolderDto> getFolderStats(String userId) {
        MailboxCredentialsResponse creds = authServerClient.fetchMailboxCredentials(userId);
        try (ImapSession session = openImap(creds)) {
            Folder inbox = openFolder(session.store(), "inbox", Folder.READ_ONLY);

            try {
                // 받은편지함만 안 읽은 수. 나머지는 전체 건수.
                int unread = inbox.getUnreadMessageCount();
                int sent = countMessages(session.store(), "sent");
                int draft = countMessages(session.store(), "draft");
                int trash = countMessages(session.store(), "trash");

                return List.of(
                        new MailFolderDto("inbox", "받은편지함", unread),
                        new MailFolderDto("sent", "보낸편지함", sent),
                        new MailFolderDto("draft", "임시보관함", draft),
                        new MailFolderDto("trash", "휴지통", trash));
            } finally {
                closeQuietly(inbox);
            }

        } catch (MessagingException ex) {
            throw new IllegalStateException("IMAP folder stats failed for user " + userId, ex);
        }
    }

    @Override
    /** 받은·보낸 최근 메일에서 상대 주소를 모아 검색어로 거르는 제안. */
    public List<MailRecipientSuggestion> suggestRecipients(String userId, String query) {
        MailboxCredentialsResponse creds = authServerClient.fetchMailboxCredentials(userId);
        String self = creds.mailAddress() == null ? "" : creds.mailAddress().toLowerCase(Locale.ROOT);
        LinkedHashMap<String, MailRecipientSuggestion> byEmail = new LinkedHashMap<>();

        try (ImapSession session = openImap(creds)) {
            // 받은·보낸 최근 메일에서 상대 주소를 모음. 내 주소는 제외.
            collectRecipientsFromFolder(session.store(), "inbox", 50, self, byEmail);
            collectRecipientsFromFolder(session.store(), "sent", 50, self, byEmail);

        } catch (MessagingException ex) {
            throw new IllegalStateException("IMAP recipient suggest failed for user " + userId, ex);
        }

        String q = query == null ? "" : query.trim();

        return byEmail.values().stream()
                .filter(item -> KoreanTextMatcher.matches(q, item.displayName(), item.email()))
                .limit(20)
                .toList();
    }

    /** 폴더 최신 메일에서 From/To/Cc를 후보 맵에 넣는 수집. */
    private void collectRecipientsFromFolder(
            Store store,
            String folder,
            int limit,
            String selfEmail,
            Map<String, MailRecipientSuggestion> byEmail)
            throws MessagingException {
        Folder imapFolder = openFolder(store, folder, Folder.READ_ONLY);

        try {
            int total = imapFolder.getMessageCount();

            if (total == 0) {
                return;
            }

            // 폴더 끝(최신)에서 limit통만.
            int start = Math.max(1, total - limit + 1);
            Message[] messages = imapFolder.getMessages(start, total);

            for (int i = messages.length - 1; i >= 0; i--) {
                Message message = messages[i];
                addAddresses(message.getFrom(), selfEmail, byEmail);
                addAddresses(message.getRecipients(Message.RecipientType.TO), selfEmail, byEmail);
                addAddresses(message.getRecipients(Message.RecipientType.CC), selfEmail, byEmail);
            }

        } finally {
            closeQuietly(imapFolder);
        }
    }

    /** 주소 배열에서 내 메일을 빼고 후보 맵에 넣는 추가. */
    private static void addAddresses(
            Address[] addresses, String selfEmail, Map<String, MailRecipientSuggestion> byEmail) {
        if (addresses == null) {
            return;
        }
        for (Address address : addresses) {
            if (!(address instanceof InternetAddress internetAddress)) {
                continue;
            }
            String email = internetAddress.getAddress();
            if (!StringUtils.hasText(email)) {
                continue;
            }
            String key = email.toLowerCase(Locale.ROOT);
            // 내 주소는 수신자 후보에서 제외.
            if (key.equals(selfEmail)) {
                continue;
            }
            String name = internetAddress.getPersonal();
            byEmail.putIfAbsent(key, MailRecipientSuggestion.of(email, name != null ? name : ""));
        }
    }

    /** Drafts에 APPEND하고 새 UID 반환. 이전 id가 다르면 그 초안 삭제. */
    private String appendDraft(MailboxCredentialsResponse creds, MimeMessage message, String oldId)
            throws MessagingException {
        try (ImapSession session = openImap(creds)) {
            Folder drafts = openFolder(session.store(), "draft", Folder.READ_WRITE);

            try {
                // 새 초안 APPEND 후 UID. 응답 UID가 없으면 맨 끝 메시지로 대체.
                IMAPFolder imapFolder = (IMAPFolder) drafts;
                AppendUID[] appended = imapFolder.appendUIDMessages(new Message[] {message});
                UIDFolder uidFolder = (UIDFolder) drafts;
                String newId = null;

                if (appended != null && appended.length > 0 && appended[0].uid > 0) {
                    newId = String.valueOf(appended[0].uid);
                } else {
                    int total = drafts.getMessageCount();

                    if (total < 1) {
                        throw new MessagingException("IMAP draft append returned no UID");
                    }

                    newId = String.valueOf(uidFolder.getUID(drafts.getMessage(total)));
                }

                // 이전 초안 UID가 다르면 그 사본 삭제.
                if (StringUtils.hasText(oldId) && !oldId.equals(newId)) {
                    try {
                        Message previous = uidFolder.getMessageByUID(parseUid(oldId));

                        if (previous != null) {
                            previous.setFlag(Flags.Flag.DELETED, true);
                            drafts.expunge();
                        }

                    } catch (ApiException ex) {
                        log.warn("Failed to delete IMAP draft {}", oldId, ex);
                    }
                }

                return newId;

            } finally {
                closeQuietly(drafts);
            }
        }
    }

    /** 원본 폴더에서 Trash로 옮기는 이동. 복원용 출발 표시를 먼저 붙임. */
    private void moveToTrash(Store store, String folder, List<String> ids) throws MessagingException {
        Folder source = openFolder(store, folder, Folder.READ_WRITE);

        try {
            Folder trash = openFolder(store, "trash", Folder.READ_WRITE);

            try {
                Message[] messages = findByUid((UIDFolder) source, ids);

                if (messages.length == 0) {
                    return;
                }

                // 복원용 출발 폴더 플래그.
                Flags origin = new Flags(originKeyword(folder));

                for (Message message : messages) {
                    message.setFlags(origin, true);
                }

                // Trash로 MOVE.
                ((IMAPFolder) source).moveMessages(messages, trash);

            } finally {
                closeQuietly(trash);
            }

        } finally {
            closeQuietly(source);
        }
    }

    /** 휴지통에서 원래 폴더로 되돌리는 복원. */
    private void restoreFromTrash(Store store, List<String> ids) throws MessagingException {
        Folder trash = openFolder(store, "trash", Folder.READ_WRITE);

        try {
            Message[] messages = findByUid((UIDFolder) trash, ids);

            if (messages.length == 0) {
                return;
            }

            // 출발 폴더별로 묶는 분류.
            Map<String, List<Message>> byFolder = new LinkedHashMap<>();

            for (Message message : messages) {
                byFolder.computeIfAbsent(originFolder(message), key -> new ArrayList<>()).add(message);
            }

            Flags originFlags = new Flags(ORIGIN_INBOX);
            originFlags.add(ORIGIN_SENT);
            originFlags.add(ORIGIN_DRAFT);

            for (Map.Entry<String, List<Message>> entry : byFolder.entrySet()) {
                Folder destination = openFolder(store, entry.getKey(), Folder.READ_WRITE);

                try {
                    Message[] batch = entry.getValue().toArray(Message[]::new);

                    // 출발 표시를 지운 뒤 원래 폴더로 MOVE.
                    for (Message message : batch) {
                        message.setFlags(originFlags, false);
                    }

                    ((IMAPFolder) trash).moveMessages(batch, destination);

                } finally {
                    closeQuietly(destination);
                }
            }

        } finally {
            closeQuietly(trash);
        }
    }

    /** 휴지통으로 넣기 전에 다는 출발 폴더 표시. */
    private static String originKeyword(String folder) {
        return switch (normalizeFolder(folder)) {
            case "sent" -> ORIGIN_SENT;
            case "draft" -> ORIGIN_DRAFT;
            default -> ORIGIN_INBOX;
        };
    }

    /** 표시가 없으면 받은편지함. \\Draft 면 임시보관함. */
    private static String originFolder(Message message) throws MessagingException {
        Flags flags = message.getFlags();

        if (flags.contains(ORIGIN_DRAFT) || message.isSet(Flags.Flag.DRAFT)) {
            return "draft";
        }

        if (flags.contains(ORIGIN_SENT)) {
            return "sent";
        }

        return "inbox";
    }

    /** 휴지통 UID에 \\Deleted 를 걸고 비우는 완전 삭제. */
    private void expungeByUid(Store store, String folder, List<String> ids) throws MessagingException {
        Folder imapFolder = openFolder(store, folder, Folder.READ_WRITE);

        try {
            Message[] messages = findByUid((UIDFolder) imapFolder, ids);

            if (messages.length == 0) {
                return;
            }

            // \\Deleted 후 expunge.
            for (Message message : messages) {
                message.setFlag(Flags.Flag.DELETED, true);
            }

            imapFolder.expunge();

        } finally {
            closeQuietly(imapFolder);
        }
    }

    /** UID 목록으로 메시지를 찾고, 없거나 잘못된 id는 건너뛰는 조회. */
    private static Message[] findByUid(UIDFolder uidFolder, List<String> ids) throws MessagingException {
        List<Message> found = new ArrayList<>();

        for (String id : ids) {
            Message message;

            try {
                message = uidFolder.getMessageByUID(parseUid(id));

            } catch (ApiException ex) {
                // 숫자가 아닌 id는 건너뜀.
                continue;
            }

            if (message != null) {
                found.add(message);
            }
        }

        return found.toArray(Message[]::new);
    }

    /** 발송 후 남은 초안 삭제. 없거나 실패해도 발송은 유지. */
    private void deleteDraftQuietly(MailboxCredentialsResponse creds, String draftId) {
        try (ImapSession session = openImap(creds)) {
            Folder drafts = openFolder(session.store(), "draft", Folder.READ_WRITE);

            try {
                long uid = parseUid(draftId);
                UIDFolder uidFolder = (UIDFolder) drafts;
                Message message = uidFolder.getMessageByUID(uid);

                if (message == null) {
                    return;
                }

                // 보낸 뒤 남은 초안 UID 삭제.
                message.setFlag(Flags.Flag.DELETED, true);
                drafts.expunge();

            } finally {
                closeQuietly(drafts);
            }

        } catch (MessagingException | ApiException ex) {
            log.warn("Failed to delete IMAP draft {}", draftId, ex);
        }
    }

    /** 폴더 전체 메시지 건수. */
    private static int countMessages(Store store, String folder) throws MessagingException {
        Folder imapFolder = openFolder(store, folder, Folder.READ_ONLY);

        try {
            return imapFolder.getMessageCount();

        } finally {
            closeQuietly(imapFolder);
        }
    }

    /**
     * IMAPS Store 연결. 호출부는 try-with-resources로 {@link ImapSession}을 닫음.
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
        // 메일함 주소·비밀번호로 접속.
        store.connect(creds.imapHost(), creds.imapPort(), creds.mailAddress(), creds.password());

        return new ImapSession(store);
    }

    /**
     * SMTP STARTTLS용 Session. Authenticator에 메일함 주소·비밀번호.
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
            /** SMTP 인증에 메일 주소와 비밀번호를 넣는 조회. */
            @Override
            protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
                return new jakarta.mail.PasswordAuthentication(creds.mailAddress(), creds.password());
            }
        });
    }

    /**
     * SPA folder id에 해당하는 IMAP Folder 오픈.
     * Sent/Drafts/Trash가 없으면 생성하고, 그래도 실패하면 예외.
     */
    private static Folder openFolder(Store store, String folder, int mode) throws MessagingException {
        String name = toImapFolderName(folder);
        Folder imapFolder = store.getFolder(name);

        // Sent/Drafts/Trash가 없으면 생성. INBOX는 만들지 않음.
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
     * pageToken을 offset 정수로 파싱.
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
                envelope.to(),
                envelope.subject(),
                previewOf(ImapMimeReader.previewText(message)),
                formatDate(envelope.sentDate()),
                envelope.unread());
    }

    /**
     * IMAP Message → 상세 DTO.
     * threadId는 MVP에서 UID와 동일. 본문은 HTML 우선, 인라인 이미지는 data URL 치환.
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
                envelope.cc(),
                envelope.bcc(),
                envelope.subject(),
                previewOf(content.preview()),
                content.body(),
                content.bodyContentType(),
                formatDate(envelope.sentDate()),
                envelope.unread(),
                content.attachments());
    }

    private record Envelope(
            long uid,
            String fromEmail,
            String fromName,
            String to,
            String cc,
            String bcc,
            String subject,
            Date sentDate,
            boolean unread) {}

    /** From/To/Cc/Bcc·제목·날짜·안 읽음 여부를 한 번에 읽는 헤더. */
    private static Envelope envelope(Message message, UIDFolder uidFolder) throws MessagingException {
        Address[] from = message.getFrom();
        String fromEmail = extractEmail(from);

        return new Envelope(
                uidFolder.getUID(message),
                fromEmail,
                extractPersonal(from, fromEmail),
                formatAddresses(message.getRecipients(Message.RecipientType.TO)),
                formatAddresses(message.getRecipients(Message.RecipientType.CC)),
                formatAddresses(message.getRecipients(Message.RecipientType.BCC)),
                message.getSubject() != null ? message.getSubject() : "(no subject)",
                message.getSentDate(),
                // SEEN이 없으면 안 읽은 메일.
                !message.isSet(Flags.Flag.SEEN));
    }

    /** From/To 주소 배열에서 첫 이메일 추출. */
    private static String extractEmail(Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return "";
        }
        if (addresses[0] instanceof InternetAddress internetAddress) {
            return internetAddress.getAddress() != null ? internetAddress.getAddress() : "";
        }

        return addresses[0].toString();
    }

    /** 수신자 헤더 표시. 여러 주소는 쉼표로 잇는 문자열. */
    private static String formatAddresses(Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Address address : addresses) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            if (address instanceof InternetAddress internetAddress) {
                sb.append(internetAddress.toUnicodeString());
            } else {
                sb.append(address.toString());
            }
        }

        return sb.toString();
    }

    /**
     * 표시 이름(personal) 추출.
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
     * {@link #openImap}이 반환하고, list/get/stats에서 쓰는 접속.
     */
    private static final class ImapSession implements AutoCloseable {
        private final Store store;

        /** 열린 Store를 감싸는 생성. */
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
