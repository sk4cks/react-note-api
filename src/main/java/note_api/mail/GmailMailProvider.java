package note_api.mail;

import note_api.auth.AuthServerClient;
import note_api.mail.dto.MailFolderDto;
import note_api.mail.dto.MailMessageDetailDto;
import note_api.mail.dto.MailMessageListDto;
import note_api.mail.dto.SendMailRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Gmail API 기반 {@link MailProvider}.
 * <p>
 * 기존 {@link MailService}가 직접 하던 Gmail 경로를 분리한 구현체.
 * {@code app.mail.provider=gmail} (k8s 기본)일 때 {@link MailService}가 이 빈을 사용한다.
 * <p>
 * 공통 흐름: JWT {@code sub}(= userId) → Auth에서 Google access token → {@link GmailClient}.
 * Google 미연동(404)이면 {@link MailGoogleNotLinkedException} → 프론트 FORBIDDEN.
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
        String googleToken = authServerClient.fetchGoogleAccessToken(userId);

        return gmailClient.listMessages(
                googleToken, folder, GmailApiConstants.DEFAULT_LIST_MAX_RESULTS, pageToken);
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
    public MailMessageDetailDto getMessage(String userId, String messageId) {
        String googleToken = authServerClient.fetchGoogleAccessToken(userId);
        MailMessageDetailDto detail = gmailClient.getMessage(googleToken, messageId);
        if (!detail.unread()) {
            return detail;
        }
        try {
            gmailClient.markThreadAsRead(googleToken, detail.threadId());

        } catch (RuntimeException ex) {
            log.warn("Failed to mark thread as read: {}", detail.threadId(), ex);

            return detail;
        }

        return new MailMessageDetailDto(
                detail.id(),
                detail.threadId(),
                detail.folder(),
                detail.from(),
                detail.fromEmail(),
                detail.to(),
                detail.subject(),
                detail.preview(),
                detail.body(),
                detail.bodyContentType(),
                detail.date(),
                false);
    }

    /**
     * 메일을 발송한다 (Gmail {@code users.messages.send}, MIME raw).
     *
     * @param userId  JWT subject
     * @param request to / subject / body
     */
    @Override
    public void sendMessage(String userId, SendMailRequest request) {
        String googleToken = authServerClient.fetchGoogleAccessToken(userId);
        gmailClient.sendMessage(googleToken, request.to(), request.subject(), request.body());
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
        String googleToken = authServerClient.fetchGoogleAccessToken(userId);

        return gmailClient.getFolderStats(googleToken);
    }
}
