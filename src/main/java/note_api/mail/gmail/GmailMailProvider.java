package note_api.mail.gmail;

import note_api.auth.AuthServerClient;
import note_api.mail.MailProvider;
import note_api.mail.dto.MailAttachmentContent;
import note_api.mail.dto.MailFolderDto;
import note_api.mail.dto.MailMessageDetailDto;
import note_api.mail.dto.MailMessageListDto;
import note_api.mail.dto.MailRecipientSuggestion;
import note_api.mail.dto.SendMailRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Gmail API 기반 {@link note_api.mail.MailProvider}.
 * <p>
 * 기존 {@link note_api.mail.MailService}가 직접 하던 Gmail 경로를 분리한 구현체.
 * {@code app.mail.provider=gmail} (k8s 기본)일 때 {@link note_api.mail.MailService}가 이 빈을 사용한다.
 * <p>
 * 공통 흐름: JWT {@code sub}(= userId) → Auth에서 Google access token → {@link GmailClient}.
 * Google 미연동(404)이면 {@link note_api.common.exception.ApiException}({@code MAIL_GOOGLE_NOT_LINKED}) → 프론트 FORBIDDEN.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GmailMailProvider implements MailProvider {

    private final AuthServerClient authServerClient;
    private final GmailClient gmailClient;

    /**
     * 폴더별 스레드/메시지 목록을 조회한다.
     *
     * @param userId    JWT subject (Auth에 저장된 Google principal 키)
     * @param folder    SPA 폴더 id — {@code inbox}/{@code sent}/{@code draft}
     * @param pageToken Gmail nextPageToken. 첫 페이지는 {@code null}
     * @return 메시지 요약 목록 + 다음 페이지 토큰
     */
    @Override
    public MailMessageListDto listMessages(String userId, String folder, String pageToken) {
        return gmailClient.listMessages(
                googleToken(userId), folder, GmailApiConstants.DEFAULT_LIST_MAX_RESULTS, pageToken);
    }

    /**
     * 메시지 상세를 조회한다. unread면 상세 열람 시 스레드를 읽음 처리한다.
     * <p>
     * mark-as-read 실패해도 본문은 반환한다 (원본 {@code unread=true} 유지).
     * 성공 시 응답 DTO의 {@code unread}만 {@code false}로 바꿔 내려 UX에 즉시 반영한다.
     *
     * @param userId    JWT subject
     * @param messageId Gmail message id
     * @return 상세 DTO (읽음 처리 반영 여부 포함)
     */
    @Override
    public MailMessageDetailDto getMessage(String userId, String folder, String messageId) {
        String token = googleToken(userId);
        MailMessageDetailDto detail = gmailClient.getMessage(token, messageId);
        if (!detail.unread()) {
            return detail;
        }
        try {
            gmailClient.markThreadAsRead(token, detail.threadId());

        } catch (RuntimeException ex) {
            log.warn("Failed to mark thread as read: {}", detail.threadId(), ex);

            return detail;
        }

        return detail.asRead();
    }

    /**
     * 첨부파일을 받는다. Gmail message id는 폴더와 무관해 {@code folder}는 쓰지 않는다.
     *
     * @param attachmentId Gmail attachmentId
     */
    @Override
    public MailAttachmentContent getAttachment(
            String userId, String folder, String messageId, String attachmentId) {
        return gmailClient.getAttachment(googleToken(userId), messageId, attachmentId);
    }

    /**
     * 메일을 발송한다 (Gmail {@code users.messages.send}, MIME raw).
     *
     * @param userId  JWT subject
     * @param request to / subject / body / attachments
     */
    @Override
    public void sendMessage(String userId, SendMailRequest request) {
        gmailClient.sendMessage(googleToken(userId), request);
    }

    /**
     * 네비/뱃지용 폴더 통계를 조회한다.
     * inbox unread thread 수, sent/draft label threadsTotal 등.
     *
     * @param userId JWT subject
     * @return 폴더 id / 표시명 / count 목록
     */
    @Override
    public List<MailFolderDto> getFolderStats(String userId) {
        return gmailClient.getFolderStats(googleToken(userId));
    }

    /** 최근 메일에서 수신자 후보 — query가 있으면 한글 초성 매칭. */
    @Override
    public List<MailRecipientSuggestion> suggestRecipients(String userId, String query) {
        return gmailClient.suggestRecipients(googleToken(userId), query);
    }

    /** Auth에 저장된 Google access token. 미연동이면 MAIL_GOOGLE_NOT_LINKED. */
    private String googleToken(String userId) {
        return authServerClient.fetchGoogleAccessToken(userId);
    }
}
